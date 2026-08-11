package com.agenttrail.runtime.tool;

public interface ToolExecutor {
    ToolExecutionResult execute(String toolName, String toolCallId, String argumentsJson);
}
