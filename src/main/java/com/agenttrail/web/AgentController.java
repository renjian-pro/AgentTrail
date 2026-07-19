package com.agenttrail.web;

import com.agenttrail.runtime.AgentRuntime;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
