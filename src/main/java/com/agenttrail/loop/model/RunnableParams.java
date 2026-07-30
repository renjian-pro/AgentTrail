package com.agenttrail.loop.model;

/**
 * 单次循环执行的运行时参数。
 *
 * <p>后续会扩展出"双通道"能力（踩坑点 #59）：一类参数模型可见、会进 prompt；
 * 另一类模型不可见，由 Runtime 在工具执行前强制注入覆盖——比如 userId 这种
 * 绝不能让模型自己填、填错就越权的系统级参数。
 */
public record RunnableParams(String conversationId, String userId) {
}
