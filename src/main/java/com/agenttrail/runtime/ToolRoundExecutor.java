package com.agenttrail.runtime;

import com.agenttrail.runtime.tool.ToolExecutionResult;
import com.agenttrail.runtime.tool.ToolExecutor;

import java.util.List;

/** Ordered tool execution seam with an explicit approval outcome. */
public final class ToolRoundExecutor {
    private final ToolExecutor toolExecutor;

    public ToolRoundExecutor(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    public ToolRoundOutcome execute(List<ToolCall> calls) {
        List<ToolExecutionResult> results = calls.stream()
                .map(call -> toolExecutor.execute(call.toolName(), call.toolCallId(), call.argumentsJson()))
                .toList();
        return new ToolRoundOutcome.Executed(results);
    }

    public sealed interface ToolRoundOutcome permits ToolRoundOutcome.Executed, ToolRoundOutcome.NeedsApproval {
        record Executed(List<ToolExecutionResult> results) implements ToolRoundOutcome {
        }

        record NeedsApproval(List<ToolCall> calls) implements ToolRoundOutcome {
        }
    }

    public record ToolCall(String toolName, String toolCallId, String argumentsJson) {
    }
}
