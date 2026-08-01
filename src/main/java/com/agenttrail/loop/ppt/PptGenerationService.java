package com.agenttrail.loop.ppt;

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
     * 新建一条任务并立即跑到底（或跑到某个状态失败为止）。意图识别只支持
     * {@link PptIntent#CREATE}（issue #24 验收范围），命中 {@code MODIFY}/{@code RESUME}
     * 直接拒绝——那是 issue #32 的范围，装作能处理只会让状态机停在一个没有对应实现的分支上。
     *
     * @return 新建任务的主键，可用于后续通过 {@link #run(long)} 恢复（如果这次没能跑到 SUCCESS）
     */
    public long create(String conversationId, String userMessage) {
        PptIntent intent = PptIntentRecognizer.recognize(userMessage);
        if (intent != PptIntent.CREATE) {
            throw new UnsupportedOperationException(
                    "PPT 生成的 " + intent + " 分支不在 issue #24 范围内（见 issue #32）: " + userMessage);
        }
        PptGenerationContext initialContext = PptGenerationContext.initial(conversationId, userMessage);
        long taskId = taskStore.create(conversationId, initialContext);
        run(taskId);
        return taskId;
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

        while (current != PptState.SUCCESS) {
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

    /** 查询一条任务当前的持久化状态（HTTP 层展示用）——不驱动任何执行，纯读。 */
    public Optional<PptTask> describe(long taskId) {
        return taskStore.findById(taskId);
    }

    /** 从任务当前的上下文快照里取出已产出的 pptx 路径；还没跑到 RENDER 完成时为 {@code null}。 */
    public String outputPathOf(long taskId) {
        return taskStore.findById(taskId)
                .map(PptTask::contextJson)
                .map(PptContextJson::fromJson)
                .map(PptGenerationContext::outputPath)
                .orElse(null);
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
