package com.agenttrail.web;

import com.agenttrail.loop.AgentLoop;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    private final AgentLoop agentLoop;

    public AgentController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @PostMapping("/agent/chat")
    public AgentChatResponse chat(@RequestBody AgentChatRequest request) {
        String answer = agentLoop.run(request.message());
        return new AgentChatResponse(answer);
    }
}
