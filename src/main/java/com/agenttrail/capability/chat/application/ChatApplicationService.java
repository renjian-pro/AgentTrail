package com.agenttrail.capability.chat.application;

import com.agenttrail.conversation.application.ConversationHistory;
import com.agenttrail.conversation.application.ConversationPage;
import com.agenttrail.conversation.application.ConversationPort;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentEvent;
import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.api.ResumeCommand;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatApplicationService {
    private final RuntimeProfileRegistry profiles;
    private final ConversationPort conversations;

    public ChatApplicationService(RuntimeProfileRegistry profiles, ConversationPort conversations) {
        this.profiles = profiles;
        this.conversations = conversations;
    }

    public Flux<EventEnvelope> send(ExecutionPrincipal principal, String conversationId,
                                    String message, ToolScope toolScope) {
        return send(principal, conversationId, null, message, toolScope);
    }

    public Flux<EventEnvelope> send(ExecutionPrincipal principal, String conversationId, String modelId,
                                    String message, ToolScope toolScope) {
        String id = conversationId == null || conversationId.isBlank()
                ? ConversationId.newId().value() : conversationId;
        AgentRuntimePort runtime = profiles.resolve("chat-default", modelId,
                toolScope == null ? ToolScope.none() : toolScope);
        AgentRunHandle handle = runtime.start(request(principal, id, message));
        return toEvents(handle, ConversationId.of(id));
    }

    public Flux<EventEnvelope> approve(ExecutionPrincipal principal, String conversationId,
                                       String modelId, boolean approved, String rejectionReason) {
        if (!conversations.belongsTo(conversationId, principal)) {
            throw new IllegalArgumentException("Conversation does not belong to user");
        }
        AgentRuntimePort runtime = profiles.resolve("chat-default", modelId, ToolScope.none());
        ResumeCommand command = approved ? new ResumeCommand.Approve()
                : new ResumeCommand.Reject(rejectionReason);
        AgentRunHandle handle = runtime.resume(RunId.of(conversationId), command);
        return toEvents(handle, ConversationId.of(conversationId));
    }

    public boolean stop(ExecutionPrincipal principal, String conversationId) {
        if (!conversations.belongsTo(conversationId, principal)) {
            return false;
        }
        AgentRuntimePort runtime = profiles.resolve("chat-default", null, ToolScope.none());
        runtime.cancel(RunId.of(conversationId), CancellationReason.USER_REQUESTED);
        return true;
    }

    public ConversationPage listConversations(ExecutionPrincipal principal, int page, int size) {
        return conversations.listConversations(principal, page, size);
    }

    public ConversationHistory history(ExecutionPrincipal principal, String conversationId, int page, int size) {
        if (!conversations.belongsTo(conversationId, principal)) {
            throw new IllegalArgumentException("Conversation does not belong to user");
        }
        return conversations.history(principal, conversationId, page, size);
    }

    private static AgentRequest request(ExecutionPrincipal principal, String conversationId, String message) {
        return new AgentRequest(ConversationId.of(conversationId),
                new com.agenttrail.platform.identity.Principal(principal.userId()), message,
                Map.of("userId", principal.userId(), "conversation_id", conversationId), null,
                AgentRequest.Budget.UNBOUNDED);
    }

    private static Flux<EventEnvelope> toEvents(AgentRunHandle handle, ConversationId conversationId) {
        AtomicLong sequence = new AtomicLong();
        return Flux.from(handle.events()).map(event -> toEvent(handle.runId(), conversationId, sequence.incrementAndGet(), event));
    }

    private static EventEnvelope toEvent(RunId runId, ConversationId conversationId, long sequence, AgentEvent event) {
        String type = switch (event) {
            case AgentEvent.Started ignored -> "RunStarted";
            case AgentEvent.TextDelta ignored -> "ModelDelta";
            case AgentEvent.ThinkingDelta ignored -> "ThinkingDelta";
            case AgentEvent.ToolStarted ignored -> "ToolStarted";
            case AgentEvent.ToolCompleted ignored -> "ToolCompleted";
            case AgentEvent.Paused ignored -> "Paused";
            case AgentEvent.Failed ignored -> "RunFailed";
            case AgentEvent.Completed ignored -> "RunCompleted";
        };
        return new EventEnvelope(java.util.UUID.randomUUID().toString(), runId, null, conversationId,
                sequence, Instant.now(), type, "chat-application", EventEnvelope.Visibility.CLIENT,
                event.toString());
    }
}
