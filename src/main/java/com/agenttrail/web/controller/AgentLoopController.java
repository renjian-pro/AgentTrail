package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.chat.application.ChatApplicationService;
import com.agenttrail.capability.chat.application.ExecutionPrincipal;
import com.agenttrail.capability.chat.application.ToolScope;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.web.dto.AgentApprovalRequest;
import com.agenttrail.web.dto.AgentChatRequest;
import com.agenttrail.web.dto.ConversationHistoryResponse;
import com.agenttrail.web.dto.ConversationPageResponse;
import com.agenttrail.web.dto.StopChatResponse;
import com.agenttrail.web.dto.PendingApprovalResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
public class AgentLoopController {
    private final ChatApplicationService chatService;

    @Autowired
    public AgentLoopController(ChatApplicationService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/agent/v1/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<EventEnvelope>> chat(@Valid @RequestBody AgentChatRequest request) {
        try {
            return asSse(chatService.send(principal(), request.conversationId(), request.modelId(),
                    request.message(), new ToolScope(request.webSearchEnabled(), true, java.util.Set.of()),
                    request.mode()));
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(BAD_REQUEST, failure.getMessage(), failure);
        }
    }

    @PostMapping(value = "/agent/v1/chat/{conversationId}/approve", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<EventEnvelope>> approve(@PathVariable String conversationId,
                                                         @RequestBody AgentApprovalRequest request) {
        try {
            return asSse(chatService.approve(principal(), conversationId, request.modelId(), request.approved(),
                    request.rejectionReason()));
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(NOT_FOUND, failure.getMessage(), failure);
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(BAD_REQUEST, failure.getMessage(), failure);
        }
    }

    @GetMapping("/agent/v1/chat/{conversationId}/pause")
    public PendingApprovalResponse pendingApproval(@PathVariable String conversationId) {
        try {
            return PendingApprovalResponse.from(chatService.pendingApproval(principal(), conversationId));
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(NOT_FOUND, failure.getMessage(), failure);
        }
    }

    @PostMapping("/agent/v1/chat/stop")
    public StopChatResponse stop(@RequestParam String conversationId) {
        boolean stopped = chatService.stop(principal(), conversationId);
        if (!stopped) {
            throw new ResponseStatusException(NOT_FOUND, "Conversation does not exist: " + conversationId);
        }
        return new StopChatResponse(conversationId, true);
    }

    @GetMapping("/agent/v1/conversations")
    public ConversationPageResponse conversations(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        var result = chatService.listConversations(principal(), page, size);
        return new ConversationPageResponse(result.page(), result.size(), result.hasMore(), result.conversations());
    }

    @GetMapping("/agent/v1/conversations/{conversationId}/history")
    public ConversationHistoryResponse history(@PathVariable String conversationId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        try {
            var result = chatService.history(principal(), conversationId, page, size);
            return new ConversationHistoryResponse(result.conversationId(), result.page(), result.size(),
                    result.hasMore(), result.turns());
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(NOT_FOUND, failure.getMessage(), failure);
        }
    }

    private ExecutionPrincipal principal() {
        return new ExecutionPrincipal(StpUtil.getLoginIdAsString(), null);
    }

    private static Flux<ServerSentEvent<EventEnvelope>> asSse(Flux<EventEnvelope> events) {
        return events.map(event -> ServerSentEvent.builder(event)
                .event(event.type())
                .id(event.eventId())
                .build());
    }
}
