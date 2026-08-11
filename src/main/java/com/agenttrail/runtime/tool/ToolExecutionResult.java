package com.agenttrail.runtime.tool;

public record ToolExecutionResult(String toolCallId, String resultJson, boolean success) {
}
