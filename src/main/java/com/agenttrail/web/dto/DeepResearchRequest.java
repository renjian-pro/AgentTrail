package com.agenttrail.web.dto;

/** {@code POST /agent/v1/deepresearch} 的请求体。 */
public record DeepResearchRequest(String conversationId, String question) {
}
