package com.agenttrail.loop.pause;

/**
 * 一个被挂起、还没执行的工具调用。故意不用 Spring AI 的 {@code AssistantMessage.ToolCall}——
 * 那个类型没有为持久化设计（要序列化进 {@link PauseState}，未来落 JDBC/Redis 时最好是个
 * 不依赖第三方库内部结构的纯数据类型），恢复时再按需转换回 {@code ToolCall}。
 *
 * @param id        工具调用 id，和恢复后拼回历史时的 {@code ToolResponse} 一一对应
 * @param name      工具名
 * @param arguments 原始参数 JSON 字符串
 */
public record PendingToolCall(String id, String name, String arguments) {
}
