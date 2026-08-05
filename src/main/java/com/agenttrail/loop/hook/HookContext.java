package com.agenttrail.loop.hook;

/** Hook 实现可见的稳定运行时上下文，不直接暴露 AgentLoopExecutor 的内部状态。 */
public record HookContext(String conversationId, String userId, int round) {
}
