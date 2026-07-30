package com.agenttrail.loop.model;

/** Runtime parameters for a single loop execution. Extended with a hidden tool-param channel in a later ticket. */
public record RunnableParams(String conversationId, String userId) {
}
