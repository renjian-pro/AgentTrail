package com.agenttrail.legacy;

import com.agenttrail.legacy.V0.AgentLoop;
import com.agenttrail.legacy.V0.AgentLoopException;
import com.agenttrail.legacy.V0.LlmResponse;
import com.agenttrail.legacy.V0.ToolCallRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    @Test
    void returnsFinalAnswerDirectlyWithoutCallingAnyTool() {
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.FinalAnswer("hello world")
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(), 5);

        String result = loop.run("say hi");

        assertThat(result).isEqualTo("hello world");
        assertThat(llmClient.callCount()).isEqualTo(1);
    }

    @Test
    void executesRequestedToolAndFeedsResultBackUntilFinalAnswer() {
        RecordingTool echoTool = new RecordingTool("echo", "Echoes the input back", "pong");
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.ToolCall(new ToolCallRequest("echo", Map.of("text", "ping"))),
                new LlmResponse.FinalAnswer("done: pong")
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(echoTool), 5);

        String result = loop.run("please echo ping");

        assertThat(result).isEqualTo("done: pong");
        assertThat(llmClient.callCount()).isEqualTo(2);
        assertThat(echoTool.executedArguments()).containsExactly(Map.of("text", "ping"));
    }

    @Test
    void throwsWhenLlmRequestsAnUnregisteredTool() {
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.ToolCall(new ToolCallRequest("does-not-exist", Map.of()))
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(), 5);

        assertThatThrownBy(() -> loop.run("do something"))
                .isInstanceOf(AgentLoopException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void throwsAfterExceedingMaxIterationsWithoutFinalAnswer() {
        RecordingTool loopingTool = new RecordingTool("noop", "does nothing useful", "still thinking");
        ScriptedLlmClient llmClient = new ScriptedLlmClient(List.of(
                new LlmResponse.ToolCall(new ToolCallRequest("noop", Map.of())),
                new LlmResponse.ToolCall(new ToolCallRequest("noop", Map.of())),
                new LlmResponse.ToolCall(new ToolCallRequest("noop", Map.of()))
        ));
        AgentLoop loop = new AgentLoop(llmClient, List.of(loopingTool), 3);

        assertThatThrownBy(() -> loop.run("keep going forever"))
                .isInstanceOf(AgentLoopException.class)
                .hasMessageContaining("3");
    }
}
