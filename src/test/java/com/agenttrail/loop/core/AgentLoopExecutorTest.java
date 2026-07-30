package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopExecutorTest {

    @Test
    void returnsFinalAnswerWithoutAnyToolCall() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("hello world")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        List<AgentStreamEvent> events = executor.stream("say hi", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new AgentStreamEvent.Text("hello world"));
        assertThat(events.get(1)).isEqualTo(new AgentStreamEvent.Complete("conv-1", null));
        assertThat(chatModel.roundCount()).isEqualTo(1);
    }
}
