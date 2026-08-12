package com.agenttrail.runtime;

import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.repository.InMemoryRunEventStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractTest {
    @Test
    void eventReplayIsStrictlyAfterTheRequestedSequence() {
        InMemoryRunEventStore store = new InMemoryRunEventStore();
        RunId runId = RunId.newId();
        TaskId taskId = TaskId.newId();
        store.append(EventEnvelope.create(runId, taskId, null, "RunStarted", "", EventEnvelope.Visibility.CLIENT, "{}"));
        store.append(EventEnvelope.create(runId, taskId, null, "RunCompleted", "", EventEnvelope.Visibility.CLIENT, "{}"));

        assertThat(store.afterSequence(runId, 0)).hasSize(2);
        assertThat(store.afterSequence(runId, 1)).hasSize(1);
    }
}
