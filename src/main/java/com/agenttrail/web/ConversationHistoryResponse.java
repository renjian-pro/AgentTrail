package com.agenttrail.web;

import java.util.List;

/** 按会话读取历史的分页响应。 */
public record ConversationHistoryResponse(String conversationId, int page, int size, boolean hasMore,
        List<ConversationTurnResponse> turns) {
}
