package com.agenttrail.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 会话输入框发送 PPT 消息的唯一写入 DTO。具体操作由后端结合任务状态判断。 */
public record PptMessageRequest(
        @NotBlank String conversationId,
        @NotBlank String message,
        String idempotencyKey) {
}
