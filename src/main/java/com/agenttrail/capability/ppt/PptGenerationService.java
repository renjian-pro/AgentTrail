package com.agenttrail.capability.ppt;

import com.agenttrail.runtime.lifecycle.InMemoryLeaseManager;
import com.agenttrail.runtime.lifecycle.LeaseManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * PPT 生成状态机的编排入口（issue #24）——把 Spring 自动收集的全部 {@link PptGenerationStrategy}
 * bean 按 {@link PptGenerationStrategy#handledState()} 建成一张分发表，{@link #run(long)}
 * 沿着固定顺序 {@code INIT→REQUIREMENT→SEARCH→TEMPLATE→OUTLINE→SCHEMA→IMAGE→RENDER→VERIFY→SUCCESS}
 * （{@code IMAGE} 是 issue #31 新增的状态：立即把文生图 API 返回的临时图片链接下载转存进 MinIO）
 * 逐个状态推进，不是散落的 if-else 链。
 *
 * <p><b>checkpoint 写入时序是这个类唯一必须严格遵守的规则</b>（对应 issue #24 明确提到的、
 * 参考实现里真实发生过的 bug：状态在配图这类副作用真正完成之前就被乐观地写成了"可以渲染"，
 * 崩溃恢复后带着缺图的 schema 直接去渲染）：{@link #run(long)} 里 {@code taskStore.advance(...)}
 * 只在 {@code strategy.execute(context)} 正常返回之后才调用——也就是说，只有当前状态该做的
 * 全部副作用（无论是一次 LLM 调用还是一次 Python 子进程渲染）都已经真正跑完，才会把 checkpoint
 * 推进到下一个状态；执行过程中抛出的任何异常都不会导致状态被提前推进，{@code current} 原地不动，
 * 失败会按稳定错误码、重试分类和 attempt 写入结构化元数据，旧客户端仍可读取 {@code errorMsg}。
 *
 * <p>{@link #run(long)} 既是"第一次跑完整个流程"的执行体（{@link #create} 内部调用它），
 * 也是"进程重启/上一次失败后重试"的恢复入口——两者是同一段代码，不是分开维护的两套逻辑：
 * 每次都是"读任务当前持久化的 {@code status}，从这个状态继续跑"，天然具备断点续传能力，
 * 恢复粒度是整个状态重新跑一遍（踩坑点 #44），不是状态内部的子步骤。
 */
public class PptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PptGenerationService.class);

    /** {@link PptState#AWAITING_INPUT} 刻意不在这条链路里：它是等人的暂停态，不是流程里的一环。 */
    private static final List<PptState> ORDER = List.of(
            PptState.INIT, PptState.CLARIFY, PptState.REQUIREMENT, PptState.SEARCH, PptState.TEMPLATE,
            PptState.OUTLINE, PptState.SCHEMA, PptState.IMAGE, PptState.RENDER, PptState.VERIFY,
            PptState.SUCCESS);

    private final PptTaskStore taskStore;
    private final Map<PptState, PptGenerationStrategy> strategiesByState;
    private final LeaseManager leaseManager;
    private final PptRetryPolicy retryPolicy;
    private final PptCancellationRegistry cancellationRegistry;
    private final PptGenerationMetrics metrics;
    private static final Duration RUN_LEASE_TTL = Duration.ofMinutes(10);
    private final java.util.Set<Long> idempotencyReplays = ConcurrentHashMap.newKeySet();

    public PptGenerationService(PptTaskStore taskStore, List<PptGenerationStrategy> strategies) {
        this(taskStore, strategies, new InMemoryLeaseManager());
    }

    public PptGenerationService(PptTaskStore taskStore, List<PptGenerationStrategy> strategies,
            LeaseManager leaseManager) {
        this(taskStore, strategies, leaseManager, PptRetryPolicy.defaults());
    }

    public PptGenerationService(PptTaskStore taskStore, List<PptGenerationStrategy> strategies,
            LeaseManager leaseManager, PptRetryPolicy retryPolicy) {
        this(taskStore, strategies, leaseManager, retryPolicy, new PptCancellationRegistry(),
                new PptGenerationMetrics(null));
    }

    public PptGenerationService(PptTaskStore taskStore, List<PptGenerationStrategy> strategies,
            LeaseManager leaseManager, PptRetryPolicy retryPolicy, PptCancellationRegistry cancellationRegistry) {
        this(taskStore, strategies, leaseManager, retryPolicy, cancellationRegistry,
                new PptGenerationMetrics(null));
    }

    /** 生产装配可注入 Micrometer；旧构造函数保持无指标的兼容行为。 */
    public PptGenerationService(PptTaskStore taskStore, List<PptGenerationStrategy> strategies,
            LeaseManager leaseManager, PptRetryPolicy retryPolicy, PptCancellationRegistry cancellationRegistry,
            PptGenerationMetrics metrics) {
        this.taskStore = taskStore;
        this.leaseManager = leaseManager;
        this.retryPolicy = retryPolicy;
        this.cancellationRegistry = cancellationRegistry;
        this.metrics = metrics == null ? new PptGenerationMetrics(null) : metrics;
        this.strategiesByState = strategies.stream()
                .collect(Collectors.toMap(PptGenerationStrategy::handledState, strategy -> strategy));
        for (PptState state : ORDER) {
            // VERIFY 是新协议的可选升级点：旧测试/旧装配没有对象存储时仍可跑到 SUCCESS；生产配置
            // 会提供 VerifyStrategy，此时 SUCCESS 必须经过硬门禁和上传。
            if (state != PptState.SUCCESS && state != PptState.VERIFY && !strategiesByState.containsKey(state)) {
                throw new IllegalStateException("缺少状态 " + state + " 对应的 PptGenerationStrategy 实现");
            }
        }
    }

    /**
     * 三分支路由入口（issue #24 骨架只做 CREATE，issue #32 补齐 MODIFY/RESUME）——按
     * {@link PptIntentRecognizer#recognize} 识别出的意图分发到对应处理，每个分支各自负责
     * "定位/新建任务 + 跑到底或跑到某个状态失败为止"，方法名沿用 {@code create} 是因为
     * HTTP 层 {@code /agent/v1/ppt/create} 这一个入口本身就承担"用户发一句话，系统自己判断
     * 该新建/该续、该改"的职责，不是调用方先自己判断意图再挑接口。
     *
     * @return 被处理任务的主键（CREATE/MODIFY 是新建的任务，RESUME 是找到的既有任务），
     *         可用于后续通过 {@link #run(long)} 恢复（如果这次没能跑到 SUCCESS）
     */
    public long create(String conversationId, String userMessage) {
        return create("legacy", conversationId, userMessage);
    }

    /** HTTP 层使用的带归属入口；userId 在这里冻结，后续断点任务只接受同一归属。 */
    public long create(String userId, String conversationId, String userMessage) {
        long taskId = prepare(userId, conversationId, userMessage);
        run(taskId);
        return taskId;
    }

    /**
     * 只做"这次该新建/续传/修改哪个任务"的判定和落库准备——意图识别 + 建/找任务行（MODIFY
     * 顺带把 checkpoint 快进到 SCHEMA），不跑状态机本身。{@link #create} 内部就是
     * {@code prepare} 紧接着 {@link #run(long)}，拆开是为了给异步入口用：HTTP 层拿到 taskId
     * 后可以立即把响应返回给前端，再把耗时的 {@code run(taskId)} 丢到后台执行器上——两种调用
     * 方式复用同一份意图识别/建档逻辑，不是分叉维护两套。
     */
    public long prepare(String userId, String conversationId, String userMessage) {
        return prepare(userId, conversationId, userMessage, null);
    }

    public long prepare(String userId, String conversationId, String userMessage, String idempotencyKey) {
        PptIntent intent = PptIntentRecognizer.recognize(userMessage);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return prepareByIntent(userId, conversationId, userMessage, intent, null);
        }
        return prepareByIntent(userId, conversationId, userMessage, intent, idempotencyKey);
    }

    private long prepareByIntent(String userId, String conversationId, String userMessage) {
        PptIntent intent = PptIntentRecognizer.recognize(userMessage);
        return prepareByIntent(userId, conversationId, userMessage, intent, null);
    }

    private long prepareByIntent(String userId, String conversationId, String userMessage,
            PptIntent intent, String idempotencyKey) {
        return switch (intent) {
            case CREATE -> prepareNew(userId, conversationId, userMessage, idempotencyKey);
            case RESUME -> prepareResume(userId, conversationId);
            case MODIFY -> prepareModify(userId, conversationId, userMessage, idempotencyKey);
        };
    }

    public boolean consumeIdempotencyReplay(long taskId) {
        return idempotencyReplays.remove(taskId);
    }

    private long prepareNew(String userId, String conversationId, String userMessage, String idempotencyKey) {
        PptGenerationContext initialContext = PptGenerationContext.initial(conversationId, userMessage);
        PptTaskCreation creation = taskStore.createIdempotent(userId, conversationId, initialContext,
                "CREATE", idempotencyKey);
        if (creation.replay()) {
            idempotencyReplays.add(creation.taskId());
        }
        return creation.taskId();
    }

    /**
     * RESUME 分支（issue #32）：按 conversationId 找到这个会话下最近一条任务，复用它已经持久化
     * 的状态粒度 checkpoint（issue #24），直接调 {@link #run(long)} 从中断的状态继续——不是另外
     * 实现一套续传逻辑，{@code run(long)} 本身既是"第一次跑完"的执行体也是"断点恢复"的入口，
     * 这里只是补上"根据 conversationId 找到 taskId"这一步，调用方（聊天入口）不需要自己记 taskId。
     */
    private long prepareResume(String userId, String conversationId) {
        PptTask existing = taskStore.findLatestByConversationId(userId, conversationId)
                .orElseThrow(() -> new IllegalStateException(
                        "会话 " + conversationId + " 下没有可以继续的 PPT 任务"));
        if (existing.status() == PptState.SUCCESS) {
            throw new IllegalStateException(
                    "会话 " + conversationId + " 最近一条 PPT 任务（id=" + existing.id() + "）已经完成，没有可继续的中断状态");
        }
        if (existing.status() == PptState.CANCELLED) {
            throw new IllegalStateException(
                    "会话 " + conversationId + " 最近一条 PPT 任务（id=" + existing.id() + "）已经取消，没有可继续的中断状态");
        }
        return existing.id();
    }

    /**
     * MODIFY 分支（issue #32）：按 conversationId 找到最近一条已经跑完（{@link PptState#SUCCESS}）
     * 的任务，复用它 REQUIREMENT/SEARCH/TEMPLATE/OUTLINE 四个状态已经产出的内容——不是重新走一遍
     * 完整流程，新任务的 checkpoint 直接从 {@link PptState#SCHEMA} 起跳，只重新执行
     * SCHEMA→IMAGE→RENDER 三个状态；用户这次的修改指令通过 {@code userRequirement} 字段带给
     * SCHEMA 状态（{@code SchemaStrategy} 会把它拼进 Prompt，见该类的类注释），不修改原任务那一行，
     * 每次 MODIFY 都新建一条任务，原始任务和历史修改记录都完整保留在 {@code ppt_generation_task}
     * 表里，不是原地覆盖。
     */
    private long prepareModify(String userId, String conversationId, String userMessage, String idempotencyKey) {
        PptTask existing = taskStore.findLatestByConversationId(userId, conversationId)
                .filter(task -> task.status() == PptState.SUCCESS)
                .orElseThrow(() -> new IllegalStateException(
                        "会话 " + conversationId + " 下没有已经生成完成的 PPT，无法在其基础上修改"));
        return prepareModifyFromBase(userId, existing, userMessage, idempotencyKey);
    }

    /**
     * 卡片修改入口按 baseTaskId 精确指定基线，不能因为会话里出现了新任务就悄悄换基线。
     * 归属和 SUCCESS 校验在这里集中完成，HTTP、异步消息和内部调用共享同一安全边界。
     */
    public long prepareModify(String userId, long baseTaskId, String userMessage, String idempotencyKey) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("修改指令不能为空");
        }
        PptTask existing = (userId == null ? taskStore.findById(baseTaskId) : taskStore.findById(userId, baseTaskId))
                .orElseThrow(() -> new IllegalArgumentException("PPT 基线任务不存在: " + baseTaskId));
        if (existing.status() != PptState.SUCCESS) {
            throw new IllegalStateException("PPT 基线任务 " + baseTaskId + " 尚未成功，不能创建修改版本");
        }
        return prepareModifyFromBase(userId, existing, userMessage, idempotencyKey);
    }

    private long prepareModifyFromBase(String userId, PptTask existing, String userMessage,
            String idempotencyKey) {
        PptGenerationContext previous = PptContextJson.fromJson(existing.contextJson());
        PptGenerationContext modifyContext = new PptGenerationContext(existing.conversationId(), userMessage,
                previous.requirement(), previous.searchMaterials(), previous.templatePath(), previous.outline(),
                previous.schema(), previous.outputPath(), null, PptGenerationContext.CURRENT_CONTEXT_VERSION,
                previous.warnings(), previous.visualPlan(), previous.assetTasks(), previous.templateRef(),
                previous.artifactRef())
                .withOperationMetadata("MODIFY", existing.id(),
                        previous.artifactRef() == null ? null : previous.artifactRef().artifactId());

        PptTaskCreation creation = taskStore.createIdempotent(userId, existing.conversationId(), modifyContext,
                "MODIFY", idempotencyKey);
        long newTaskId = creation.taskId();
        if (creation.replay()) {
            idempotencyReplays.add(newTaskId);
            return newTaskId;
        }
        // 新任务默认从 INIT 起步（taskStore.create 的固定行为），这里立即把 checkpoint 快进到
        // SCHEMA——INIT/REQUIREMENT/SEARCH/TEMPLATE/OUTLINE 都不需要真的执行一遍，这一行本身就是
        // "定位到已有记录、在其基础上改，不重新走完整流程"这条验收标准的具体落地。
        taskStore.advance(newTaskId, PptState.SCHEMA, modifyContext);
        return newTaskId;
    }

    /**
     * 从任务当前持久化的状态继续跑，直到 {@link PptState#SUCCESS} 或抛出
     * {@link PptGenerationException}。
     */
    public void run(long taskId) {
        PptTask task = taskStore.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PPT 任务不存在: " + taskId));
        String leaseKey = "ppt-generation:" + taskId;
        if (!leaseManager.tryAcquire(leaseKey, RUN_LEASE_TTL)) {
            log.info("PPT 任务 {} 已由另一个 worker 持有执行租约，跳过重复执行", taskId);
            return;
        }
        PptGenerationContext context = PptContextJson.fromJson(task.contextJson());
        PptState current = task.status();
        long revision = task.revision();
        PptCancellationToken cancellationToken = cancellationRegistry.tokenFor(taskId);
        try {
            if (task.runStatus() == PptRunStatus.CANCEL_REQUESTED || taskStore.isCancelRequested(taskId)) {
                taskStore.markCancelled(taskId, current);
                return;
            }
            if (current == PptState.SUCCESS || current == PptState.CANCELLED || current == PptState.AWAITING_INPUT) {
                return;
            }
            if (!taskStore.claim(taskId, current, revision)) {
                return;
            }
            revision++;
            while (current != PptState.SUCCESS && current != PptState.CANCELLED
                    && current != PptState.AWAITING_INPUT) {
                if (!leaseManager.renew(leaseKey, RUN_LEASE_TTL)) {
                    throw new PptGenerationException("PPT 任务执行租约已丢失: " + taskId);
                }
                if (taskStore.isCancelRequested(taskId)) {
                    cancellationRegistry.cancel(taskId);
                    taskStore.markCancelled(taskId, current);
                    return;
                }
                PptGenerationStrategy strategy = strategiesByState.get(current);
                if (strategy == null) {
                    throw new IllegalStateException("没有登记状态 " + current + " 对应的 Strategy 实现");
                }
                int stageAttempt = taskStore.findById(taskId).map(PptTask::attempt).orElse(1);
                log.info("PPT stage started taskId={} conversationId={} stage={} attempt={} revision={}",
                        taskId, task.conversationId(), current, stageAttempt, revision);
                long stageStartedAt = System.nanoTime();
                try {
                    context = strategy.execute(context, cancellationToken);
                    metrics.stageSucceeded(current, System.nanoTime() - stageStartedAt);
                } catch (PptCancellationException cancelled) {
                    metrics.taskCancelled();
                    taskStore.markCancelled(taskId, current);
                    return;
                } catch (Exception executionFailed) {
                    String diagnostic = describeFailure(executionFailed);
                    log.error("PPT 任务 {} 在状态 {} 失败: {}", taskId, current, diagnostic, executionFailed);
                    com.agenttrail.platform.error.RetryClass retryClass = PptFailureClassifier.classify(executionFailed);
                    if (retryClass == com.agenttrail.platform.error.RetryClass.NONE) {
                        retryClass = com.agenttrail.platform.error.RetryClass.FATAL;
                    }
                    metrics.stageFailed(current, System.nanoTime() - stageStartedAt, retryClass.name());
                    String errorMsg = userFailureMessage(current, stableFailureCode(executionFailed));
                    int attempt = Math.max(taskStore.findById(taskId).map(PptTask::attempt).orElse(1), 1);
                    PptFailure failure = new PptFailure(stableFailureCode(executionFailed), current,
                            retryClass == com.agenttrail.platform.error.RetryClass.RETRIABLE
                                    || retryClass == com.agenttrail.platform.error.RetryClass.RATE_LIMITED,
                            retryClass, attempt, errorMsg, System.currentTimeMillis());
                    boolean retry = failure.retryable() && retryPolicy.canRetry(attempt);
                    PptRunStatus failureStatus = retry ? PptRunStatus.RETRY_WAIT : PptRunStatus.FAILED;
                    long nextRetryAt = retry
                            ? System.currentTimeMillis() + retryPolicy.delayMillisForNextAttempt(attempt) : 0;
                    if (!taskStore.recordFailureIfCurrent(taskId, current, revision, failure,
                            failureStatus, nextRetryAt)) {
                        throw new PptCheckpointConflictException(taskId, current, revision);
                    }
                    if (!retry) metrics.taskFailed();
                    throw new PptGenerationException("PPT 生成在状态 " + current + " 失败: " + errorMsg, executionFailed);
                }
                if (!leaseManager.renew(leaseKey, RUN_LEASE_TTL)) {
                    throw new PptLeaseLostException(taskId);
                }
                if (taskStore.isCancelRequested(taskId)) {
                    cancellationRegistry.cancel(taskId);
                    taskStore.markCancelled(taskId, current);
                    return;
                }
                // CLARIFY 判定信息不足时不往下走，落到 AWAITING_INPUT 等用户补充——用的还是
                // advance（而不是 markFailed）：等人不是失败，errorMsg 必须保持为空，否则前端会
                // 把一次正常的追问渲染成一条错误，而 runningTaskIdsFor 也会把它当成出错任务筛掉。
                if (current == PptState.CLARIFY && context.clarifyingQuestion() != null) {
                    if (!taskStore.conditionalAdvance(taskId, current, revision,
                            PptState.AWAITING_INPUT, PptRunStatus.WAITING_INPUT, context)) {
                        throw new PptCheckpointConflictException(taskId, current, revision);
                    }
                    return;
                }
                PptState next = nextState(current);
                PptRunStatus nextRunStatus = next == PptState.SUCCESS
                        ? PptRunStatus.SUCCEEDED : PptRunStatus.RUNNING;
                if (!taskStore.conditionalAdvance(taskId, current, revision, next, nextRunStatus, context)) {
                    throw new PptCheckpointConflictException(taskId, current, revision);
                }
                log.info("PPT checkpoint committed taskId={} conversationId={} from={} to={} revision={}",
                        taskId, task.conversationId(), current, next, revision + 1);
                revision++;
                current = next;
            }
            metrics.taskSucceeded();
        } finally {
            leaseManager.release(leaseKey);
            cancellationRegistry.remove(taskId);
        }
    }

    public void run(String userId, long taskId) {
        taskStore.findById(userId, taskId)
                .orElseThrow(() -> new java.util.NoSuchElementException("PPT 任务不存在: " + taskId));
        run(taskId);
    }

    /**
     * 用户回答澄清追问后的续接入口，对应 {@code DeepResearchService#continueAfterClarification}。
     *
     * <p><b>只追问一轮，而且这条约束是结构性的、不靠标志位。</b>这里把 checkpoint 直接推到
     * {@link PptState#REQUIREMENT}——{@link PptState#CLARIFY} 被整个跳过，所以物理上不存在"再问一次"
     * 的路径。DeepResearch 那边是靠"{@code continueAfterClarification} 内部不调 {@code needsMoreInfo}"
     * 达成同一件事，都是把"不再打断"写进控制流，而不是加一个"已经问过了"的布尔字段——布尔字段总有
     * 忘记置位的分支，跳过状态没有。
     *
     * <p>拼接格式和 DeepResearch 的三段式一致：模型下一步（REQUIREMENT）读到的是"原始需求 + 我问了什么
     * + 用户答了什么"的完整上下文，而不是孤零零一句回答——只喂回答的话，"给市场部的，20 分钟"这种
     * 补充脱离了追问就完全不知所云。
     *
     * @throws IllegalStateException 任务不在 {@link PptState#AWAITING_INPUT}：没在等人的任务没有"回答"可言，
     *         静默接受只会把一句回答当成新需求覆盖掉正在跑的任务
     */
    public void answerClarification(String userId, long taskId, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("澄清回答不能为空");
        }
        PptTask task = (userId == null ? taskStore.findById(taskId) : taskStore.findById(userId, taskId))
                .orElseThrow(() -> new IllegalArgumentException("PPT 任务不存在: " + taskId));
        if (task.status() != PptState.AWAITING_INPUT) {
            throw new IllegalStateException(
                    "PPT 任务 " + taskId + " 当前状态是 " + task.status() + "，没有在等待补充信息");
        }
        PptGenerationContext context = PptContextJson.fromJson(task.contextJson());
        String combined = """
                【此前的 PPT 需求】
                %s

                【助手追问】
                %s

                【用户补充】
                %s""".formatted(context.userRequirement(), context.clarifyingQuestion(), answer);
        taskStore.advance(taskId, PptState.REQUIREMENT, context.withUserRequirement(combined));
    }

    /** 查询一条任务当前的持久化状态（HTTP 层展示用）——不驱动任何执行，纯读。 */
    public Optional<PptTask> describe(long taskId) {
        return taskStore.findById(taskId);
    }

    public Optional<PptTask> describe(String userId, long taskId) {
        return taskStore.findById(userId, taskId);
    }

    /** 会话历史查询只读且带用户归属条件，返回结果已按最新版本在前排序。 */
    public List<PptTask> describeConversation(String userId, String conversationId) {
        if (userId == null || userId.isBlank()) return List.of();
        if (conversationId == null || conversationId.isBlank()) return List.of();
        return taskStore.findAllByConversationId(userId, conversationId);
    }

    /** 批量状态查询有明确上限，防止把 taskId 接口变成无界数据库扫描。 */
    public List<PptTask> describeMany(String userId, List<Long> taskIds) {
        if (userId == null || userId.isBlank() || taskIds == null || taskIds.isEmpty()) return List.of();
        if (taskIds.size() > 100) throw new IllegalArgumentException("一次最多查询 100 个 PPT 任务");
        return taskIds.stream().filter(java.util.Objects::nonNull).distinct()
                .map(id -> taskStore.findById(userId, id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 统一任务视图装配使用的只读元数据入口；不驱动状态机。 */
    public List<PptCheckpointEvent> eventsOf(long taskId) {
        return taskStore.eventsForTask(taskId);
    }

    public PptGenerationContext contextOf(PptTask task) {
        return PptContextJson.fromJson(task.contextJson());
    }

    public PptFailure failureOf(PptTask task) {
        return task.failureJson() == null || task.failureJson().isBlank()
                ? null : PptFailureJson.fromJson(task.failureJson());
    }

    public List<PptWarning> warningsOf(PptTask task) {
        if (task.warningsJson() != null && !task.warningsJson().isBlank()) {
            return PptWarningsJson.fromJson(task.warningsJson());
        }
        return contextOf(task).warnings();
    }

    /** 请求在下一个状态边界停止；实际终态由 {@link #run(long)} 写入。 */
    public void requestCancel(long taskId) {
        taskStore.requestCancel(taskId);
        cancellationRegistry.cancel(taskId);
    }

    /** Persist a user-visible failure when the asynchronous executor rejected a submission. */
    public void markSchedulingFailure(long taskId, String errorMsg) {
        taskStore.findById(taskId).ifPresent(task -> {
            if (task.status() != PptState.SUCCESS && task.status() != PptState.CANCELLED
                    && task.status() != PptState.AWAITING_INPUT) {
                taskStore.markFailed(taskId, task.status(), errorMsg);
            }
        });
    }

    public List<Long> runningTaskIdsFor(String userId) {
        return taskStore.runningTaskIdsFor(userId);
    }

    /**
     * 取出待用户回答的澄清追问；只有任务确实停在 {@link PptState#AWAITING_INPUT} 时才返回非空。
     *
     * <p>状态判断放在这里而不是交给调用方：上下文里的 {@code clarifyingQuestion} 在用户答完之后
     * 是**不清空**的（留作可追溯的原文），直接读字段会让一条已经在正常推进的任务继续对外宣称
     * "我在等你回答"。"在不在等人"的唯一权威是状态，不是这个字段。
     */
    public String pendingClarifyingQuestionOf(String userId, long taskId) {
        return (userId == null ? taskStore.findById(taskId) : taskStore.findById(userId, taskId))
                .filter(task -> task.status() == PptState.AWAITING_INPUT)
                .map(PptTask::contextJson)
                .map(PptContextJson::fromJson)
                .map(PptGenerationContext::clarifyingQuestion)
                .orElse(null);
    }

    /** 从任务当前的上下文快照里取出已产出的 pptx 路径；还没跑到 RENDER 完成时为 {@code null}。 */
    public String outputPathOf(long taskId) {
        return taskStore.findById(taskId)
                .map(PptTask::contextJson)
                .map(PptContextJson::fromJson)
                .map(PptGenerationContext::outputPath)
                .orElse(null);
    }

    public String outputPathOf(String userId, long taskId) {
        return taskStore.findById(userId, taskId)
                .map(PptTask::contextJson).map(PptContextJson::fromJson)
                .map(PptGenerationContext::outputPath).orElse(null);
    }

    private PptState nextState(PptState current) {
        int index = ORDER.indexOf(current);
        for (int i = index + 1; i < ORDER.size(); i++) {
            PptState candidate = ORDER.get(i);
            if (candidate == PptState.VERIFY && !strategiesByState.containsKey(candidate)) {
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException("状态没有后继: " + current);
    }

    private static String describeFailure(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    /** 任务表只保存稳定、有限长度的用户提示；原始异常仅留在服务端日志。 */
    private static String userFailureMessage(PptState state, String code) {
        return switch (code) {
            case "PPT_TIMEOUT" -> "PPT 生成超时，请稍后重试";
            case "PPT_SCHEMA_INVALID" -> "PPT 结构生成失败，请检查需求后重试";
            case "PPT_TEMPLATE_INVALID" -> "PPT 模板不可用，请更换模板后重试";
            case "PPT_RATE_LIMITED" -> "服务繁忙，请稍后重试";
            default -> "PPT 在 " + state + " 阶段执行失败，请稍后重试";
        };
    }

    /** 稳定错误码只由异常类型/受控关键字生成，不把供应商响应或本机路径返回给前端。 */
    private static String stableFailureCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (message.contains("timeout") || message.contains("timed out")) return "PPT_TIMEOUT";
            if (message.contains("schema") || message.contains("json")) return "PPT_SCHEMA_INVALID";
            if (message.contains("template")) return "PPT_TEMPLATE_INVALID";
            if (message.contains("rate limit") || message.contains("429")) return "PPT_RATE_LIMITED";
            current = current.getCause();
        }
        return "PPT_STAGE_FAILED";
    }
}
