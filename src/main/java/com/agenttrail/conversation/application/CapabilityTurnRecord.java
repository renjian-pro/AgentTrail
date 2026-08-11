package com.agenttrail.conversation.application;

public record CapabilityTurnRecord(String userId, String conversationId, String question,
                                   String answer, String capability, Object payload,
                                   String error, long totalResponseTimeMillis) {
}
