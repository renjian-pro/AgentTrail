package com.agenttrail.capability.deepresearch;

import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;

/**
 * 深度研究任务的**元信息**（issue #108 / R20）。
 *
 * <p><b>刻意不存报告正文。</b>完成的报告本来就随 {@code CapabilityConversationService.record()}
 * 落进 {@code agent_session.timeline}（`StageOutput{stage:"research", payload:<报告>}`），前端
 * 历史回放读的就是那一份。在这里再存一份就是把同一个产物存两处，两份必然漂移。
 *
 * <p>这张表要解决的是另一个问题：{@code DeepResearchController} 的 {@code handles} 和
 * {@code publicIds} 原本全在内存里，应用一重启，一个进行中的任务就查不到了——前端轮询拿到 404，
 * 而 404 的语义是"这个任务从来不存在"，和事实（"它存在过，被重启打断了"）正好相反。
 * 顺带一提，{@code publicIds} 那个 {@code AtomicLong} 重启后从 0 重新开始，新任务会复用旧编号。
 *
 * <p><b>明确不做续跑。</b>{@code DeepResearchService#research} 是一次不带 checkpoint 的单体调用
 * （澄清→主题→逐任务检索→综合报告全在一次方法调用里），要做到 PPT 那种"重启后凭 taskId 继续"
 * 得先把它拆成状态机，和 PPT 状态机同量级。这一票只把"404 查无此任务"换成一个诚实的终态。
 *
 * <p><b>状态直接用 {@link DeepResearchTaskStatus}</b>，不另立一套字符串常量——这四个值
 * worker 侧已经有了，{@code DeepResearchTaskResponse} 又有一份线上字符串，再加第三套就意味着
 * 每加一个状态要改三个词汇表，而且 {@code markTerminal(long, String, String)} 这种签名下打错字
 * 是能编译过的。落库用 {@code name()}、读回用 {@code valueOf}，和 {@code JdbcPptTaskStore}
 * 存 {@code PptState} 的做法一致。
 *
 * @param id             主键，同时就是对外的 taskId——自增保证重启后不复用编号
 * @param userId         发起人，查询与取消都要做归属校验
 * @param conversationId 发起这个任务的会话
 * @param question       用户的原始提问，用于列表展示和排查
 * @param errorMsg       失败原因；成功或进行中为 null
 */
public record ResearchTaskRecord(
        Long id,
        String userId,
        String conversationId,
        String question,
        DeepResearchTaskStatus status,
        String errorMsg,
        long createdAtMillis,
        long updatedAtMillis) {

    /**
     * 重启扫描给进行中任务写的失败原因。
     *
     * <p>措辞要让用户看得懂发生了什么、以及该怎么办——"任务失败"这种话会让他以为是研究本身
     * 出了问题，然后反复重试同一个问题。
     */
    public static final String INTERRUPTED_BY_RESTART = "服务重启，任务已中断，请重新发起";

    public boolean isRunning() {
        return status == DeepResearchTaskStatus.RUNNING;
    }
}
