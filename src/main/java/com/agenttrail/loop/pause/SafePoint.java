package com.agenttrail.loop.pause;

/**
 * 暂停发生时循环正处于哪个阶段——恢复要从这个点接续，而不是从头重新规划一轮。
 *
 * <p>目前只有一个值：暂停只在"模型已经给出工具调用、但还没执行"这一处触发（见
 * {@code AgentLoopExecutor#pauseForApproval}）。单独建这个类型而不是把这层语义并进
 * {@link PauseReason} 里，是为将来加别的暂停点（比如"发起 LLM 调用前"）留出位置——
 * 到那时 {@code reason × safePoint} 的组合数会变多，现在先把这个维度立住。
 */
public enum SafePoint {
    BEFORE_TOOL_EXECUTION
}
