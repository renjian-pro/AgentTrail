package com.agenttrail.loop.pause;

import java.util.Set;

/**
 * 暂停机制的装配参数，打包成一个对象传给 {@code AgentLoopExecutor} 的构造函数——
 * 那个构造函数已经有好几个可选机制各占一个参数位，暂停需要两个协作的参数（名单 + 存储），
 * 拆成两个位置只会让调用点更难读，不如包一个小对象。
 *
 * @param approvalRequiredTools 命中这个名单的工具调用会触发暂停等审批，而不是直接执行；
 *                              空集合等价于完全不启用 HITL 审批机制
 * @param store                 暂停状态存取，通常是 {@link InMemoryPauseStateStore}（开发期）
 *                              或未来的 JDBC/Redis 实现
 */
public record PauseConfig(Set<String> approvalRequiredTools, PauseStateStore store) {

    public PauseConfig {
        approvalRequiredTools = Set.copyOf(approvalRequiredTools);
    }

    public boolean requiresApproval(String toolName) {
        return approvalRequiredTools.contains(toolName);
    }
}
