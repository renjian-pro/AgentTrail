package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.hook.SessionBudgetTracker;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static com.agenttrail.loop.core.support.ChatResponses.usage;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopExecutorBudgetTest {

    @Test
    void stopsBeforeSchedulingAnotherModelRoundWhenBudgetIsExceeded() {
        RecordingToolCallback echo = new RecordingToolCallback("echo", "echo", "pong");
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{}"), usage(8, 5)),
                List.of());
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model, List.of(echo), 5)
                .budgetTracker(new SessionBudgetTracker(10))
                .build();

        List<AgentStreamEvent> events = executor.stream("echo", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(model.roundCount()).isEqualTo(1);
        assertThat(echo.recordedArguments()).containsExactly("{}");
        assertThat(events).contains(new AgentStreamEvent.Text(
                "本会话 token 消耗已超过预算上限，本轮到此为止——如需继续，请开启新会话。"));
        assertThat(events).anyMatch(event -> event instanceof AgentStreamEvent.Complete);
    }
}
