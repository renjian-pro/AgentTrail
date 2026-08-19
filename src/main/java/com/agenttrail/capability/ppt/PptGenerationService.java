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
import java.util.concurrent.TimeUnit;
import java.time.Duration;

/**
 * PPT 生成状态机的编排入口（issue #24）——把 Spring 自动收集的全部 {@link PptGenerationStrategy}
 * bean 按 {@link PptGenerationStrategy#handledState()} 建成一张分发表，{@link #run(long)}
 * 沿着固定顺序 {@code INIT→REQUIREMENT→SEARCH→VISUAL_PLAN→TEMPLATE→OUTLINE→SCHEMA→IMAGE→RENDER→VERIFY→SUCCESS}
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
            PptState.INIT, PptState.CLARIFY, PptState.REQUIREMENT, PptState.SEARCH, PptState.VISUAL_PLAN,
            PptState.TEMPLATE,
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
            // VERIFY 是 SUCCESS 前的硬门禁：没有验签、重新打开和对象存储上传，就不能把任务标成成功。
            // 这样断点恢复也不会绕过交付链路，避免数据库里出现“成功但没有可下载产物”的任务。
            if (state != PptState.SUCCESS && !strategiesByState.containsKey(state)) {
                throw new IllegalStateException("缺少状态 " + state + " 对应的 PptGenerationStrategy 实现");
            }
        }
    }

    /** 同步测试/内部入口：显式新建并跑完；会话意图只由 {@code PptMessageRouter} 处理。 */
    public long create(String conversationId, String userMessage) {
        return create("legacy", conversationId, userMessage);
    }

    /** HTTP 层使用的带归属入口；userId 在这里冻结，后续断点任务只接受同一归属。 */
    public long create(String userId, String conversationId, String userMessage) {
        long taskId = prepareCreate(userId, conversationId, userMessage, null);
        run(taskId);
        return taskId;
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

    /** 显式 CREATE 入口。统一消息路由已经完成意图判断时不能再次走旧关键词识别。 */
    public long prepareCreate(String userId, String conversationId, String userMessage, String idempotencyKey) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("PPT 消息不能为空");
        }
        return prepareNew(userId, conversationId, userMessage, idempotencyKey);
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

    /**
     * 生成中的修改不能原地改变正在被 worker 读取的上下文。调用方先请求取消旧任务，再用旧需求与
     * 最新指令创建一条关联的新任务；新任务从 INIT 重跑，避免复用尚未完成或相互矛盾的中间产物。
     */
    public long prepareReplacement(String userId, long baseTaskId, String userMessage, String idempotencyKey) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("修改指令不能为空");
        }
        PptTask existing = (userId == null ? taskStore.findById(baseTaskId) : taskStore.findById(userId, baseTaskId))
                .orElseThrow(() -> new IllegalArgumentException("PPT 基线任务不存在: " + baseTaskId));
        PptGenerationContext previous = PptContextJson.fromJson(existing.contextJson());
        String previousRequirement = previous.userRequirement() == null ? "" : previous.userRequirement();
        String combined = previousRequirement + "\n用户最新修改要求：" + userMessage.trim();
        PptGenerationContext replacement = PptGenerationContext.initial(existing.conversationId(), combined)
                .withOperationMetadata("MODIFY", existing.id(),
                        previous.artifactRef() == null ? null : previous.artifactRef().artifactId());
        PptTaskCreation creation = taskStore.createIdempotent(userId, existing.conversationId(), replacement,
                "MODIFY", idempotencyKey);
        if (creation.replay()) idempotencyReplays.add(creation.taskId());
        log.info("PPT active task superseded baseTaskId={} replacementTaskId={} conversationId={}",
                existing.id(), creation.taskId(), existing.conversationId());
        return creation.taskId();
    }

    private long prepareModifyFromBase(String userId, PptTask existing, String userMessage,
            String idempotencyKey) {
        PptGenerationContext previous = PptContextJson.fromJson(existing.contextJson());
        PptGenerationContext modifyContext = new PptGenerationContext(existing.conversationId(), userMessage,
                previous.requirement(), previous.searchMaterials(), previous.templatePath(), previous.outline(),
                previous.schema(), previous.outputPath(), null, PptGenerationContext.CURRENT_CONTEXT_VERSION,
                previous.warnings(), previous.visualPlan(), previous.templateRef(),
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
        PptState current = task.status();
        long revision = task.revision();
        PptCancellationToken cancellationToken = cancellationRegistry.tokenFor(taskId);
        try {
            PptGenerationContext context;
            try {
                context = PptContextJson.fromJson(task.contextJson());
            } catch (PptContextMigrationException migrationFailed) {
                // 快照迁移发生在 Strategy 之前，不能让异常绕过失败 checkpoint；否则恢复扫描会
                // 不断重试同一份坏数据。将它落成不可重试 FAILED，管理员可以按稳定 code 处理。
                String code = migrationFailed.code();
                PptFailure failure = new PptFailure(code, current, false,
                        com.agenttrail.platform.error.RetryClass.FATAL,
                        Math.max(task.attempt(), 1), "PPT 上下文版本无法迁移，请重新创建任务",
                        System.currentTimeMillis());
                if (current != PptState.SUCCESS && current != PptState.CANCELLED
                        && current != PptState.AWAITING_INPUT) {
                    taskStore.recordFailureIfCurrent(taskId, current, revision, failure,
                            PptRunStatus.FAILED, 0);
                }
                metrics.taskFailed();
                throw new PptGenerationException("PPT 上下文迁移失败: " + code, migrationFailed);
            }
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
                    context = strategy.execute(context, cancellationToken,
                            (stage, message, warningCode) -> appendProgress(taskId, stage, message, warningCode));
                    metrics.stageSucceeded(current, System.nanoTime() - stageStartedAt);
                } catch (PptCancellationException cancelled) {
                    metrics.taskCancelled();
                    taskStore.markCancelled(taskId, current);
                    log.info("PPT stage cancelled taskId={} conversationId={} stage={} attempt={} durationMs={}",
                            taskId, task.conversationId(), current, stageAttempt,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStartedAt));
                    return;
                } catch (Exception executionFailed) {
                    String diagnostic = describeFailure(executionFailed);
                    log.error("PPT stage failed taskId={} conversationId={} stage={} attempt={} revision={} durationMs={} diagnostic={}",
                            taskId, task.conversationId(), current, stageAttempt, revision,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStartedAt), diagnostic,
                            executionFailed);
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
                // 主题是进入 SEARCH 前唯一的硬门禁。CLARIFY 负责早期追问；REQUIREMENT 对结构化
                // 输出再做一次服务端校验，避免模型返回空 topic 却被状态机继续提交。
                if (current == PptState.REQUIREMENT
                        && (context.requirement() == null || !context.requirement().hasValidTopic())) {
                    context = context.withRequirement(null)
                            .withClarifyingQuestion("这份 PPT 主要想讲什么主题？");
                    log.info("PPT requirement gate blocked taskId={} conversationId={} state={} revision={} topicPresent=false",
                            taskId, task.conversationId(), current, revision);
                }
                // CLARIFY/REQUIREMENT 判定信息不足时不往下走，落到 AWAITING_INPUT 等用户补充——用的还是
                // advance（而不是 markFailed）：等人不是失败，errorMsg 必须保持为空，否则前端会
                // 把一次正常的追问渲染成一条错误，而 runningTaskIdsFor 也会把它当成出错任务筛掉。
                if ((current == PptState.CLARIFY || current == PptState.REQUIREMENT)
                        && context.clarifyingQuestion() != null) {
                    if (!taskStore.conditionalAdvance(taskId, current, revision,
                            PptState.AWAITING_INPUT, PptRunStatus.WAITING_INPUT, context)) {
                        throw new PptCheckpointConflictException(taskId, current, revision);
                    }
                    log.info("PPT awaiting input taskId={} conversationId={} fromState={} revision={} reason=missing-topic",
                            taskId, task.conversationId(), current, revision + 1);
                    return;
                }
                PptState next = nextState(current);
                PptRunStatus nextRunStatus = next == PptState.SUCCESS
                        ? PptRunStatus.SUCCEEDED : PptRunStatus.RUNNING;
                if (!taskStore.conditionalAdvance(taskId, current, revision, next, nextRunStatus, context)) {
                    throw new PptCheckpointConflictException(taskId, current, revision);
                }
                log.info("PPT stage completed taskId={} conversationId={} stage={} nextStage={} attempt={} revision={} durationMs={}",
                        taskId, task.conversationId(), current, next, stageAttempt, revision + 1,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stageStartedAt));
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
     * <p>回答后回到 {@link PptState#CLARIFY}，重新判断是否已经获得有效主题。此前直接跳到 REQUIREMENT
     * 等价于“用户只要回答过一次就必须开工”，回答“随便”也会穿透门禁；现在允许只针对主题进行多轮
     * 澄清，直到主题有效或用户取消。
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
        taskStore.advance(taskId, PptState.CLARIFY,
                context.withUserRequirement(combined).withClarifyingQuestion(null));
        log.info("PPT clarification accepted taskId={} conversationId={} nextState={} answerLength={}",
                taskId, task.conversationId(), PptState.CLARIFY, answer.length());
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

    private void appendProgress(long taskId, PptState stage, String message, String warningCode) {
        try {
            taskStore.appendProgressEvent(taskId, stage, message, warningCode);
            log.info("PPT detail progress taskId={} stage={} warningCode={} message={}",
                    taskId, stage, warningCode, message);
        } catch (RuntimeException persistenceFailure) {
            // 细节进度写失败不能把已经成功生成的图片判成失败；阶段 checkpoint 仍是恢复权威。
            log.warn("PPT detail progress persistence failed taskId={} stage={} warningCode={}",
                    taskId, stage, warningCode, persistenceFailure);
        }
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
            return ORDER.get(i);
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
            case "PPT_CONTEXT_MIGRATION_FAILED" -> "PPT 上下文版本无法迁移，请重新创建任务";
            case "PPT_TIMEOUT" -> "PPT 生成超时，请稍后重试";
            case "PPT_SCHEMA_INVALID" -> "PPT 结构生成失败，请检查需求后重试";
            case "PPT_TEMPLATE_INVALID" -> "PPT 模板不可用，请更换模板后重试";
            case "PPT_RATE_LIMITED" -> "服务繁忙，请稍后重试";
            default -> "PPT 在 " + state + " 阶段执行失败，请稍后重试";
        };
    }

    /** 稳定错误码只由异常类型/受控关键字生成，不把供应商响应或本机路径返回给前端。 */
    private static String stableFailureCode(Throwable failure) {
        if (failure instanceof PptContextMigrationException migrationFailed) return migrationFailed.code();
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
