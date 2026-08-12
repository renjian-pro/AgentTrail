package com.agenttrail.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PPT 生成的 HTTP 入口请求体（issue #24）。 */
public record PptGenerationRequest(
        @Size(max = 100) String conversationId,
        @NotBlank @Size(max = 20000) String message,
        @Size(max = 200) String idempotencyKey) {
    public PptGenerationRequest(String conversationId, String message) {
        this(conversationId, message, null);
    }
}
