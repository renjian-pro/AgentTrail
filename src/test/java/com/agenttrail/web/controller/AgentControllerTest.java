package com.agenttrail.web.controller;
import com.agenttrail.web.dto.AgentChatResponse;
import com.agenttrail.web.dto.AgentChatRequest;

import com.agenttrail.legacy.V0.AgentRuntime;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    void marksTheVersionOfTheLegacyHttpRuntime() {
        AgentController controller = new AgentController(message -> "ok");
        HttpServletResponse response = mock(HttpServletResponse.class);

        controller.chat(new AgentChatRequest("hello"), response);

        verify(response).setHeader("X-Runtime-Version", "v0");
    }
}
