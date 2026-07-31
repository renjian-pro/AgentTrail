package com.agenttrail.web;

/** {@code POST /agent/v1/deepresearch} 的请求体。 */
public record DeepResearchRequest(String question) {
}
