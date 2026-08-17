package com.agenttrail.runtime.api;

import com.agenttrail.platform.error.ErrorCode;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;

import java.util.List;

public sealed interface AgentEvent {
    record Started(RunId runId, ConversationId conversationId) implements AgentEvent {
    }

    record TextDelta(RunId runId, String content) implements AgentEvent {
    }

    record ThinkingDelta(RunId runId, String content) implements AgentEvent {
    }

    record ToolStarted(RunId runId, String toolName, String toolCallId,
                       String arguments) implements AgentEvent {
    }

    record ToolCompleted(RunId runId, String toolName, String toolCallId,
                         String result) implements AgentEvent {
    }

    record Paused(RunId runId, ConversationId conversationId, String reason,
                  List<PendingTool> pendingTools) implements AgentEvent {

        public Paused {
            pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
        }

        public Paused(RunId runId, ConversationId conversationId, String reason) {
            this(runId, conversationId, reason, List.of());
        }
    }

    record PendingTool(String toolCallId, String toolName, String arguments, String riskLevel) {
    }

    record Failed(RunId runId, ErrorCode errorCode, String message) implements AgentEvent {
    }

    record Completed(RunId runId, ConversationId conversationId, Long turnId) implements AgentEvent {
    }
}
