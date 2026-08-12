package com.agenttrail.web.controller;
import com.agenttrail.web.dto.AgentChatResponse;
import com.agenttrail.web.config.AgentRuntimeConfig;
import com.agenttrail.web.dto.AgentChatRequest;

import com.agenttrail.legacy.V0.AgentRuntime;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;

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
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request, HttpServletResponse response) {
        response.setHeader("X-Runtime-Version", "v0");
        String answer = runtime.respond(request.message());
        return new AgentChatResponse(answer);
    }

    /** Direct-call compatibility for existing non-HTTP callers and unit tests. */
    public AgentChatResponse chat(AgentChatRequest request) {
        return new AgentChatResponse(runtime.respond(request.message()));
    }
}
