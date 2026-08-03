package com.agenttrail.web;

import java.util.List;

/** 会话侧栏的分页响应，避免首次进页把全部历史和全文一起读回。 */
public record ConversationPageResponse(int page, int size, boolean hasMore,
        List<ConversationSummaryResponse> sessions) {
}
