package com.agenttrail.conversation.application;

import com.agenttrail.web.dto.ConversationTurnResponse;

import java.util.List;

public record ConversationHistory(String conversationId, int page, int size, boolean hasMore,
                                  List<ConversationTurnResponse> turns) {
}
