package com.agenttrail.web;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.task.AgentTaskManager;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Flux;
import cn.dev33.satoken.stp.StpUtil;

import java.util.Map;

import java.util.UUID;

/**
 * V1 引擎（{@code loop.core.AgentLoopExecutor}）独立的 HTTP 入口——不复用 V0 的
 * {@link AgentController}/{@code /agent/chat}，两者互不影响。
 *
 * <p>这里直接订阅 {@link AgentLoopExecutor#stream} 返回的事件流并映射为 SSE。普通文本会逐段
 * 推送；工具调用分片只在 Runtime 内部按 toolCallId 重组，完整一轮后再执行工具，不把半截
 * arguments 暴露给浏览器。
 *
 * <p>普通对话、DeepResearch 和 PPT 共用 {@code agent_session} 会话事实源；请求传入已有
 * {@code conversationId} 时继续追加同一会话，未传入时才创建新的会话标识。
 *
 * <p>请求体的 {@code modelId} 是可选的模型标识（issue #20），不传时 {@link AgentLoopExecutorFactory}
 * 落到默认模型（{@code qwen-plus}）。不支持同一会话中途换模型——调用方要在同一个会话里
 * 保持用同一个模型标识。
 */
@RestController
public class AgentLoopController {

    private final AgentLoopExecutorFactory executorFactory;
    private final AgentTaskManager agentTaskManager;
    private final ConversationHistoryService conversationHistoryService;

    public AgentLoopController(AgentLoopExecutorFactory executorFactory, AgentTaskManager agentTaskManager,
            ConversationHistoryService conversationHistoryService) {
        this.executorFactory = executorFactory;
        this.agentTaskManager = agentTaskManager;
        this.conversationHistoryService = conversationHistoryService;
    }

    @PostMapping(value = "/agent/v1/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> chat(@RequestBody AgentChatRequest request) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString() : request.conversationId();
        String userId = StpUtil.getLoginIdAsString();
        RunnableParams params = new RunnableParams(conversationId, userId,
                Map.of("userId", userId, "conversation_id", conversationId), null);
        AgentLoopExecutor executor;
        try {
            executor = "analytics".equalsIgnoreCase(request.mode())
                    ? executorFactory.forAnalytics(request.modelId())
                    : executorFactory.forModelWithCharts(request.modelId(), request.webSearchEnabled());
        } catch (IllegalStateException failure) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, failure.getMessage(), failure);
        }
        return executor
                .stream(request.message(), params)
                .map(event -> ServerSentEvent.builder(event)
                        .event(event.getClass().getSimpleName())
                        .build());
    }

    @PostMapping("/agent/v1/chat/stop")
    public StopChatResponse stop(@RequestParam String conversationId) {
        String userId = StpUtil.getLoginIdAsString();
        if (!conversationHistoryService.belongsToUser(conversationId, userId)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "会话不存在: " + conversationId);
        }
        return new StopChatResponse(conversationId, agentTaskManager.stopTask(conversationId));
    }

    @GetMapping("/agent/v1/conversations")
    public ConversationPageResponse conversations(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return conversationHistoryService.findConversations(StpUtil.getLoginIdAsString(), page, size);
    }

    @GetMapping("/agent/v1/conversations/{conversationId}/history")
    public ConversationHistoryResponse history(@org.springframework.web.bind.annotation.PathVariable String conversationId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (!conversationHistoryService.belongsToUser(conversationId, StpUtil.getLoginIdAsString())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "会话不存在: " + conversationId);
        }
        return conversationHistoryService.findPage(StpUtil.getLoginIdAsString(), conversationId, page, size);
    }
}
