package com.agenttrail.conversation.application;

import com.agenttrail.capability.chat.application.ExecutionPrincipal;
import com.agenttrail.web.service.CapabilityConversationService;
import com.agenttrail.web.service.ConversationHistoryService;

public final class JdbcConversationPort implements ConversationPort {
    private final ConversationHistoryService historyService;
    private final CapabilityConversationService capabilityService;

    public JdbcConversationPort(ConversationHistoryService historyService,
                                 CapabilityConversationService capabilityService) {
        this.historyService = historyService;
        this.capabilityService = capabilityService;
    }

    @Override
    public ConversationPage listConversations(ExecutionPrincipal principal, int page, int size) {
        var response = historyService.findConversations(principal.userId(), page, size);
        return new ConversationPage(response.page(), response.size(), response.hasMore(), response.sessions());
    }

    @Override
    public ConversationHistory history(ExecutionPrincipal principal, String conversationId, int page, int size) {
        var response = historyService.findPage(principal.userId(), conversationId, page, size);
        return new ConversationHistory(response.conversationId(), response.page(), response.size(),
                response.hasMore(), response.turns());
    }

    @Override
    public boolean belongsTo(String conversationId, ExecutionPrincipal principal) {
        return historyService.belongsToUser(conversationId, principal.userId());
    }

    @Override
    public Long recordCapabilityResult(CapabilityTurnRecord record) {
        if (record.error() == null) {
            return capabilityService.recordSuccess(record.userId(), record.conversationId(), record.question(),
                    record.answer(), record.capability(), record.payload(), record.totalResponseTimeMillis());
        }
        return capabilityService.recordFailure(record.userId(), record.conversationId(), record.question(),
                record.capability(), record.error(), record.totalResponseTimeMillis());
    }
}
