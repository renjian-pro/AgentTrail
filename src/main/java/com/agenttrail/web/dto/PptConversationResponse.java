package com.agenttrail.web.dto;

/** PPT 模式会话响应：MESSAGE 表示仍在确认需求，TASK 表示生成任务此刻才真正创建。 */
public record PptConversationResponse(
        String kind,
        String assistantMessage,
        PptGenerationResponse task) {
}
