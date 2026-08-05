package com.agenttrail.loop.hook;

/** 一次具体的工具调用。 */
public record ToolInvocation(String toolCallId, String toolName, String arguments) {
}
