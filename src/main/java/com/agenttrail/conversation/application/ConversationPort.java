package com.agenttrail.conversation.application;

import com.agenttrail.capability.chat.application.ExecutionPrincipal;

public interface ConversationPort {
    ConversationPage listConversations(ExecutionPrincipal principal, int page, int size);

    ConversationHistory history(ExecutionPrincipal principal, String conversationId, int page, int size);

    boolean belongsTo(String conversationId, ExecutionPrincipal principal);

    Long recordCapabilityResult(CapabilityTurnRecord record);

    Long recordCancelled(CapabilityTurnRecord record);
}
