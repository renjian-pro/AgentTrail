package com.agenttrail.capability.ppt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * PPT 生成状态机的编排入口（issue #24）——把 Spring 自动收集的全部 {@link PptGenerationStrategy}
 * bean 按 {@link PptGenerationStrategy#handledState()} 建成一张分发表，{@link #run(long)}
 * 沿着固定顺序 {@code INIT→REQUIREMENT→SEARCH→TEMPLATE→OUTLINE→SCHEMA→IMAGE→RENDER→SUCCESS}
 * （{@code IMAGE} 是 issue #31 新增的状态：立即把文生图 API 返回的临时图片链接下载转存进 MinIO）
 * 逐个状态推进，不是散落的 if-else 链。
 *
 * <p><b>checkpoint 写入时序是这个类唯一必须严格遵守的规则</b>（对应 issue #24 明确提到的、
 * 参考实现里真实发生过的 bug：状态在配图这类副作用真正完成之前就被乐观地写成了"可以渲染"，
 * 崩溃恢复后带着缺图的 schema 直接去渲染）：{@link #run(long)} 里 {@code taskStore.advance(...)}
 * 只在 {@code strategy.execute(context)} 正常返回之后才调用——也就是说，只有当前状态该做的
 * 全部副作用（无论是一次 LLM 调用还是一次 Python 子进程渲染）都已经真正跑完，才会把 checkpoint
 * 推进到下一个状态；执行过程中抛出的任何异常都不会导致状态被提前推进，{@code current} 原地不动，
 * 只有 {@code errorMsg} 被记录下来。
 *
 * <p>{@link #run(long)} 既是"第一次跑完整个流程"的执行体（{@link #create} 内部调用它），
 * 也是"进程重启/上一次失败后重试"的恢复入口——两者是同一段代码，不是分开维护的两套逻辑：
 * 每次都是"读任务当前持久化的 {@code status}，从这个状态继续跑"，天然具备断点续传能力，
 * 恢复粒度是整个状态重新跑一遍（踩坑点 #44），不是状态内部的子步骤。
 */
public class PptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PptGenerationService.class);

    private static final List<PptState> ORDER = List.of(
            PptState.INIT, PptState.REQUIREMENT, PptState.SEARCH, PptState.TEMPLATE,
            PptState.OUTLINE, PptState.SCHEMA, PptState.IMAGE, PptState.RENDER, PptState.SUCCESS);

    private final PptTaskStore taskStore;
    private final Map<PptState, PptGenerationStrategy> strategiesByState;

    public PptGenerationService(PptTaskStore taskStore, List<PptGenerationStrategy> strategies) {
        this.taskStore = taskStore;
        this.strategiesByState = strategies.stream()
                .collect(Collectors.toMap(PptGenerationStrategy::handledState, strategy -> strategy));
        for (PptState state : ORDER) {
            if (state != PptState.SUCCESS && !strategiesByState.containsKey(state)) {
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
        PptIntent intent = PptIntentRecognizer.recognize(userMessage);
        return switch (intent) {
            case CREATE -> prepareNew(userId, conversationId, userMessage);
            case RESUME -> prepareResume(userId, conversationId);
            case MODIFY -> prepareModify(userId, conversationId, userMessage);
        };
    }

    private long prepareNew(String userId, String conversationId, String userMessage) {
        PptGenerationContext initialContext = PptGenerationContext.initial(conversationId, userMessage);
        return taskStore.create(userId, conversationId, initialContext);
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
    private long prepareModify(String userId, String conversationId, String userMessage) {
        PptTask existing = taskStore.findLatestByConversationId(userId, conversationId)
                .filter(task -> task.status() == PptState.SUCCESS)
                .orElseThrow(() -> new IllegalStateException(
                        "会话 " + conversationId + " 下没有已经生成完成的 PPT，无法在其基础上修改"));
        PptGenerationContext previous = PptContextJson.fromJson(existing.contextJson());
        PptGenerationContext modifyContext = new PptGenerationContext(conversationId, userMessage,
                previous.requirement(), previous.searchMaterials(), previous.templatePath(), previous.outline(),
                previous.schema(), previous.outputPath());

        long newTaskId = taskStore.create(userId, conversationId, modifyContext);
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
        PptGenerationContext context = PptContextJson.fromJson(task.contextJson());
        PptState current = task.status();

        while (current != PptState.SUCCESS && current != PptState.CANCELLED) {
            if (taskStore.isCancelRequested(taskId)) {
                taskStore.markCancelled(taskId, current);
                return;
            }
            PptGenerationStrategy strategy = strategiesByState.get(current);
            if (strategy == null) {
                throw new IllegalStateException("没有登记状态 " + current + " 对应的 Strategy 实现");
            }
            try {
                context = strategy.execute(context);
            } catch (Exception executionFailed) {
                String errorMsg = describeFailure(executionFailed);
                log.error("PPT 任务 {} 在状态 {} 失败: {}", taskId, current, errorMsg, executionFailed);
                taskStore.markFailed(taskId, current, errorMsg);
                throw new PptGenerationException("PPT 生成在状态 " + current + " 失败: " + errorMsg, executionFailed);
            }
            PptState next = nextState(current);
            // 状态转移只在 strategy.execute 真正返回（对应状态的副作用已经完成）之后才落库——
            // 这是避免类文档开头那个 checkpoint 顺序 bug 的关键一行，不能挪到 try 块之前。
            taskStore.advance(taskId, next, context);
            current = next;
        }
    }

    public void run(String userId, long taskId) {
        taskStore.findById(userId, taskId)
                .orElseThrow(() -> new java.util.NoSuchElementException("PPT 任务不存在: " + taskId));
        run(taskId);
    }

    /** 查询一条任务当前的持久化状态（HTTP 层展示用）——不驱动任何执行，纯读。 */
    public Optional<PptTask> describe(long taskId) {
        return taskStore.findById(taskId);
    }

    public Optional<PptTask> describe(String userId, long taskId) {
        return taskStore.findById(userId, taskId);
    }

    /** 请求在下一个状态边界停止；实际终态由 {@link #run(long)} 写入。 */
    public void requestCancel(long taskId) {
        taskStore.requestCancel(taskId);
    }

    public List<Long> runningTaskIdsFor(String userId) {
        return taskStore.runningTaskIdsFor(userId);
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

    private static PptState nextState(PptState current) {
        int index = ORDER.indexOf(current);
        return ORDER.get(index + 1);
    }

    private static String describeFailure(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
