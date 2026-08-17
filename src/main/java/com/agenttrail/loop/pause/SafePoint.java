package com.agenttrail.loop.pause;

/**
 * 暂停发生时循环正处于哪个阶段——恢复要从这个点接续，而不是从头重新规划一轮。
 *
 * <p>审批恢复前后各有一个安全点：工具执行前等待用户决定；工具执行后先持久化结果，再继续
 * 调用模型。第二个安全点让恢复请求失败后可以安全重试，而不会重复执行已经产生副作用的工具。
 */
public enum SafePoint {
    BEFORE_TOOL_EXECUTION,
    AFTER_TOOL_EXECUTION
}
