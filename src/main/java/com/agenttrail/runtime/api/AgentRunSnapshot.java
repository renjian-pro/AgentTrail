package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;

public record AgentRunSnapshot(RunId runId, ConversationId conversationId,
                               RunStatus status, int roundCount) {
    public enum RunStatus {
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED,
        UNKNOWN
    }
}
