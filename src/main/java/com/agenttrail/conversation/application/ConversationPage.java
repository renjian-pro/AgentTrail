package com.agenttrail.conversation.application;

import com.agenttrail.web.dto.ConversationSummaryResponse;

import java.util.List;

public record ConversationPage(int page, int size, boolean hasMore,
                               List<ConversationSummaryResponse> conversations) {
}
