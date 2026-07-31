package com.agenttrail.web;

import com.agenttrail.legacy.V0.AgentRuntime;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * V0 演示入口——{@code AgentRuntime} 目前唯一的实现是 {@code V0.AgentScopeRuntime}
 * （见 {@link AgentRuntimeConfig}）。V1（{@code loop.core.AgentLoopExecutor}）走独立的
 * {@link AgentLoopController}，不复用这个入口。
 */
@RestController
public class AgentController {

    private final AgentRuntime runtime;

    public AgentController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping("/agent/chat")
    public AgentChatResponse chat(@RequestBody AgentChatRequest request) {
        String answer = runtime.respond(request.message());
        return new AgentChatResponse(answer);
    }
}
