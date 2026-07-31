package com.agenttrail.web;

import com.agenttrail.legacy.V0.AgentRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentControllerTest {

    @Test
    void returnsTheRuntimeAnswerForAChatRequest() {
        List<String> receivedInputs = new ArrayList<>();
        AgentRuntime runtime = userInput -> {
            receivedInputs.add(userInput);
            return "hi there";
        };
        AgentController controller = new AgentController(runtime);

        AgentChatResponse response = controller.chat(new AgentChatRequest("hello"));

        assertThat(response.answer()).isEqualTo("hi there");
        assertThat(receivedInputs).containsExactly("hello");
    }
}
