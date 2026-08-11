package com.agenttrail.runtime.tool;

import java.util.List;

/** Runtime-facing composition of tool visibility and execution. */
public interface ToolGateway {
    List<ToolDefinition> resolve(ToolResolutionContext context);

    ToolExecutionResult execute(String toolName, String toolCallId, String argumentsJson);
}
