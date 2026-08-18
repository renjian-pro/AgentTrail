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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatApplicationService {
    private final RuntimeProfileRegistry profiles;
    private final ConversationPort conversations;
    private final PausedRunPort pausedRuns;

    public ChatApplicationService(RuntimeProfileRegistry profiles, ConversationPort conversations) {
        this(profiles, conversations, PausedRunPort.NONE);
    }

    public ChatApplicationService(RuntimeProfileRegistry profiles, ConversationPort conversations,
                                  PausedRunPort pausedRuns) {
        this.profiles = profiles;
        this.conversations = conversations;
        this.pausedRuns = pausedRuns;
    }

    public Flux<EventEnvelope> send(ExecutionPrincipal principal, String conversationId,
                                    String message, ToolScope toolScope) {
        return send(principal, conversationId, null, message, toolScope, null);
    }

    public Flux<EventEnvelope> send(ExecutionPrincipal principal, String conversationId, String modelId,
                                    String message, ToolScope toolScope) {
        return send(principal, conversationId, modelId, message, toolScope, null);
    }

    /**
     * @param mode 前端 {@code AgentChatRequest#mode()} 原样传入；目前只认 {@code "analytics"}——
     *             命中时写进 {@link AgentRequest#toolParams()} 的 {@code analyticsEnabled} 键，
     *             {@link ChatToolScopeRuntimeAdapter} 据此把整个执行器切到
     *             {@code AgentLoopExecutorFactory#forAnalytics}，其它取值（含 null）一律按普通聊天处理。
     */
    public Flux<EventEnvelope> send(ExecutionPrincipal principal, String conversationId, String modelId,
                                    String message, ToolScope toolScope, String mode) {
        String id = conversationId == null || conversationId.isBlank()
                ? ConversationId.newId().value() : conversationId;
        ToolScope scope = toolScope == null ? ToolScope.none() : toolScope;
        boolean analyticsEnabled = "analytics".equals(mode);
        AgentRuntimePort runtime = profiles.resolve("chat-default", modelId, scope);
        AgentRunHandle handle = runtime.start(request(principal, id, message, scope, analyticsEnabled));
        return toEvents(handle, ConversationId.of(id));
    }

    public Flux<EventEnvelope> approve(ExecutionPrincipal principal, String conversationId,
                                       String modelId, boolean approved, String rejectionReason) {
        PausedRunPort.PausedRun paused = requireOwnedPause(principal, conversationId);
        // 旧快照没有 modelId 时也不能相信恢复请求重传的值；传 null 让服务端注册表选择默认模型，
        // 否则客户端能把同一份服务端上下文切到任意模型上继续执行。
        String originalModelId = paused.modelId() == null || paused.modelId().isBlank()
                ? null : paused.modelId();
        ToolScope originalScope = new ToolScope(paused.webSearchEnabled(), true, java.util.Set.of());
        AgentRuntimePort runtime = profiles.resolve("chat-default", originalModelId, originalScope);
        ResumeCommand command = approved ? new ResumeCommand.Approve()
                : new ResumeCommand.Reject(rejectionReason);
        AgentRunHandle handle = runtime.resume(RunId.of(conversationId), command);
        return toEvents(handle, ConversationId.of(conversationId));
    }

    public PausedRunPort.PausedRun pendingApproval(ExecutionPrincipal principal, String conversationId) {
        return requireOwnedPause(principal, conversationId);
    }

    private PausedRunPort.PausedRun requireOwnedPause(ExecutionPrincipal principal, String conversationId) {
        return pausedRuns.find(conversationId)
                .filter(paused -> java.util.Objects.equals(paused.userId(), principal.userId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Paused conversation does not exist: " + conversationId));
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

    /** webSearchEnabled/analyticsEnabled 走 toolParams 而不是加 AgentRequest 字段——运行时（见
     * ChatToolScopeRuntimeAdapter）据此决定这次 start() 用哪套执行器，图表工具始终无条件带上。 */
    private static AgentRequest request(ExecutionPrincipal principal, String conversationId, String message,
                                        ToolScope toolScope, boolean analyticsEnabled) {
        return new AgentRequest(ConversationId.of(conversationId),
                new com.agenttrail.platform.identity.Principal(principal.userId()), message,
                Map.of("userId", principal.userId(), "conversation_id", conversationId,
                        "webSearchEnabled", toolScope.webSearch(),
                        "analyticsEnabled", analyticsEnabled),
                null, AgentRequest.Budget.UNBOUNDED);
    }

    private static Flux<EventEnvelope> toEvents(AgentRunHandle handle, ConversationId conversationId) {
        AtomicLong sequence = new AtomicLong();
        return Flux.from(handle.events()).map(event -> toEvent(handle.runId(), conversationId, sequence.incrementAndGet(), event));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

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
                payloadJson(event));
    }

    /** 前端从 SSE data 帧里按事件类型取具体字段渲染——payload 必须是这些字段的 JSON，不是 record 的 toString()。 */
    private static String payloadJson(AgentEvent event) {
        Map<String, Object> fields = switch (event) {
            case AgentEvent.Started e -> Map.of("conversationId", e.conversationId().value());
            case AgentEvent.TextDelta e -> Map.of("content", e.content());
            case AgentEvent.ThinkingDelta e -> Map.of("content", e.content());
            case AgentEvent.ToolStarted e -> Map.of("toolName", e.toolName(), "toolCallId", e.toolCallId(),
                    "arguments", e.arguments());
            case AgentEvent.ToolCompleted e -> Map.of("toolName", e.toolName(), "toolCallId", e.toolCallId(),
                    "result", e.result());
            case AgentEvent.Paused e -> Map.of(
                    "conversationId", e.conversationId().value(),
                    "reason", e.reason(),
                    "pendingTools", e.pendingTools());
            case AgentEvent.Failed e -> Map.of("code", e.errorCode().code(), "message", e.message());
            case AgentEvent.Completed e -> {
                Map<String, Object> completed = new LinkedHashMap<>();
                completed.put("conversationId", e.conversationId().value());
                completed.put("turnId", e.turnId());
                yield completed;
            }
        };
        try {
            return JSON.writeValueAsString(fields);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize agent event payload: " + event, failure);
        }
    }
}
