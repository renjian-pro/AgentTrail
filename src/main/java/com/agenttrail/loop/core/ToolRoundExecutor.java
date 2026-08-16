package com.agenttrail.loop.core;

import com.agenttrail.runtime.tool.ToolExecutionResult;
import com.agenttrail.runtime.tool.ToolExecutor;

import java.util.List;
import java.util.Objects;

/** Ordered tool execution seam with an explicit approval outcome. */
public final class ToolRoundExecutor {
    private final ToolExecutor toolExecutor;

    public ToolRoundExecutor() {
        this.toolExecutor = null;
    }

    public ToolRoundExecutor(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    public ToolRoundOutcome execute(List<ToolCall> calls) {
        Objects.requireNonNull(toolExecutor, "toolExecutor");
        List<ToolExecutionResult> results = calls.stream()
                .map(call -> toolExecutor.execute(call.toolName(), call.toolCallId(), call.argumentsJson()))
                .toList();
        return new ToolRoundOutcome.Executed(results);
    }

    /** Validate the normalized call shape before the legacy executor performs policy-aware dispatch. */
    public void validate(List<ToolCall> calls) {
        if (calls == null) {
            throw new IllegalArgumentException("tool calls are required");
        }
        calls.forEach(call -> {
            if (call == null || call.toolName() == null || call.toolName().isBlank()
                    || call.toolCallId() == null || call.toolCallId().isBlank()) {
                throw new IllegalArgumentException("tool call must contain an id and name");
            }
        });
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
