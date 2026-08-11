package com.agenttrail.runtime.api;

import com.agenttrail.loop.model.OutputType;
import com.agenttrail.platform.identity.Principal;
import com.agenttrail.platform.ids.ConversationId;

import java.time.Duration;
import java.util.Map;

public record AgentRequest(
        ConversationId conversationId,
        Principal principal,
        String message,
        Map<String, Object> toolParams,
        OutputType outputType,
        Budget budget) {

    public record Budget(Long maxTokens, Duration maxWallClock) {
        public static final Budget UNBOUNDED = new Budget(null, null);
    }
}
