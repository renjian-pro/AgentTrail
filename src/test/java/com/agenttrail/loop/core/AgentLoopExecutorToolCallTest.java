package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopExecutorToolCallTest {

    @Test
    void executesRequestedToolThenReturnsFinalAnswer() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "Echoes the input back", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{\"text\":\"ping\"}")),
                List.of(text("done: pong"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5);

        List<AgentStreamEvent> events = executor.stream("please echo ping", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).containsExactly(
                new AgentStreamEvent.ToolStart("echo", "call-1", "{\"text\":\"ping\"}"),
                new AgentStreamEvent.ToolEnd("echo", "call-1", "pong"),
                new AgentStreamEvent.Text("done: pong"),
                new AgentStreamEvent.Complete("conv-1")
        );
        assertThat(echoTool.recordedArguments()).containsExactly("{\"text\":\"ping\"}");
        assertThat(chatModel.roundCount()).isEqualTo(2);
    }
}
