package com.agenttrail.web;

/** PPT 生成的 HTTP 入口请求体（issue #24）。 */
public record PptGenerationRequest(String conversationId, String message) {
}
