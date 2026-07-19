package com.agenttrail.web;

import com.agenttrail.loop.AgentLoop;
import com.agenttrail.loop.LlmResponse;
import com.agenttrail.loop.support.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentControllerTest {

    @Test
    void returnsTheAgentLoopAnswerForAChatRequest() {
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.FinalAnswer("hi there")
        ));
        AgentLoop agentLoop = new AgentLoop(llmClient, List.of(), 5);
        AgentController controller = new AgentController(agentLoop);

        AgentChatResponse response = controller.chat(new AgentChatRequest("hello"));

        assertThat(response.answer()).isEqualTo("hi there");
        assertThat(llmClient.callCount()).isEqualTo(1);
    }
}
