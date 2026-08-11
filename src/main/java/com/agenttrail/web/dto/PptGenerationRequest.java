package com.agenttrail.web.dto;

/** PPT 生成的 HTTP 入口请求体（issue #24）。 */
public record PptGenerationRequest(String conversationId, String message, String idempotencyKey) {
    public PptGenerationRequest(String conversationId, String message) {
        this(conversationId, message, null);
    }
}
