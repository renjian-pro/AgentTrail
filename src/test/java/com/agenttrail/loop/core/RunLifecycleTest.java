package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.CancellationReason;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

class RunLifecycleTest {
    @Test
    void cancellationStopsTheRegisteredRunAndClosesItsSink() {
        AgentTaskManager tasks = new AgentTaskManager();
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        String conversationId = "run-lifecycle-test";
        assertThat(tasks.registerTask(conversationId, sink)).isTrue();

        boolean stopped = new RunLifecycleManager(tasks).cancel(RunId.of(conversationId),
                CancellationReason.USER_REQUESTED);

        assertThat(stopped).isTrue();
        assertThat(tasks.hasRunningTask(conversationId)).isFalse();
    }
}
