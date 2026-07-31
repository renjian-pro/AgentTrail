package com.agenttrail.web;

import com.agenttrail.loop.core.AgentCallException;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.RunnableParams;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * V1 引擎（{@code loop.core.AgentLoopExecutor}）独立的 HTTP 入口——不复用 V0 的
 * {@link AgentController}/{@code /agent/chat}，两者互不影响。
 *
 * <p>先用同步的 {@link AgentLoopExecutor#call} 而不是流式 {@code stream()}：接口形状和 V0 的
 * {@code /agent/chat} 保持一致（同样是 {@link AgentChatRequest}/{@link AgentChatResponse}），
 * 便于先跑通装配这一步；要换成 SSE 推流，把返回类型换成 {@code Flux<AgentStreamEvent>}、
 * {@code produces = MediaType.TEXT_EVENT_STREAM_VALUE} 即可，不需要动 {@link AgentLoopExecutorConfig}
 * 里的装配。
 *
 * <p>这里没有落库、没有会话历史（{@link AgentLoopExecutorConfig} 没配 {@code persistenceHook}），
 * 所以每次请求各生成一个新的 conversationId 即可，不影响任何行为——多轮会话记忆是后续要接的机制，
 * 不是这个最小入口的范围。
 *
 * <p>请求体的 {@code modelId} 是可选的模型标识（issue #20），不传时 {@link AgentLoopExecutorFactory}
 * 落到默认模型（{@code qwen-plus}）。不支持同一会话中途换模型——调用方要在同一个会话里
 * 保持用同一个模型标识。
 */
@RestController
public class AgentLoopController {

    private final AgentLoopExecutorFactory executorFactory;

    public AgentLoopController(AgentLoopExecutorFactory executorFactory) {
        this.executorFactory = executorFactory;
    }

    @PostMapping("/agent/v1/chat")
    public AgentChatResponse chat(@RequestBody AgentChatRequest request) {
        RunnableParams params = new RunnableParams(UUID.randomUUID().toString(), "anonymous");
        try {
            String answer = executorFactory.forModelWithCharts(request.modelId(), request.webSearchEnabled())
                    .call(request.message(), params);
            return new AgentChatResponse(answer);
        } catch (AgentCallException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, failure.getMessage(), failure);
        }
    }
}
