package com.agenttrail.runtime.infrastructure;

import com.agenttrail.loop.task.InterruptBroadcaster;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.CapabilityId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.lifecycle.PersistentCancellationPort;
import com.agenttrail.runtime.outbox.InMemoryOutboxStore;
import com.agenttrail.runtime.outbox.OutboxPublisher;
import com.agenttrail.runtime.repository.InMemoryCheckpointStore;
import com.agenttrail.runtime.repository.InMemoryRunEventStore;
import com.agenttrail.runtime.repository.InMemoryRunRepository;
import com.agenttrail.runtime.task.InMemoryTaskQueue;
import com.agenttrail.runtime.task.TaskPayload;
import com.agenttrail.runtime.task.TaskRecord;
import com.agenttrail.runtime.task.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ticket15InfrastructureTest {
    @Test
    void checkpointAndEventStoresSupportLatestAndReplay() {
        TaskId taskId = TaskId.newId();
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        checkpoints.save(taskId, "PLAN", "one");
        checkpoints.save(taskId, "SEARCH", "two");
        assertEquals("two", checkpoints.latest(taskId).orElseThrow().stateSnapshot());
        assertEquals("one", checkpoints.find(taskId, 1).orElseThrow().stateSnapshot());

        RunId runId = RunId.newId();
        InMemoryRunEventStore events = new InMemoryRunEventStore();
        events.append(EventEnvelope.create(runId, taskId, null, "RunStarted", "test",
                EventEnvelope.Visibility.CLIENT, "{}"));
        events.append(EventEnvelope.create(runId, taskId, null, "ModelDelta", "test",
                EventEnvelope.Visibility.CLIENT, "{}"));
        assertEquals(2, events.afterSequence(runId, 0).size());
        assertEquals(1, events.afterSequence(runId, 1).size());
    }

    @Test
    void queueRequeuesUnacknowledgedTasksAfterVisibilityTimeout() throws Exception {
        InMemoryTaskQueue queue = new InMemoryTaskQueue();
        TaskId taskId = TaskId.newId();
        queue.enqueue(taskId, new TaskPayload(Map.of("key", "value")));
        assertTrue(queue.poll("worker", Duration.ofMillis(20)).isPresent());
        assertTrue(queue.poll("worker-2", Duration.ofMillis(20)).isEmpty());
        Thread.sleep(40);
        assertTrue(queue.poll("worker-2", Duration.ofSeconds(1)).isPresent());
    }

    @Test
    void cancellationIsDurableEvenWhenNoListenerIsPresent() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        TaskId taskId = TaskId.newId();
        Instant now = Instant.now();
        repository.save(new TaskRecord(taskId, CapabilityId.of("deepresearch"), "default", "user",
                null, RunStatus.PENDING, null, 0, null, null, null, 0, null, null, null, now, now));
        AtomicBoolean broadcast = new AtomicBoolean();
        InterruptBroadcaster broadcaster = new InterruptBroadcaster() {
            @Override public void broadcastStop(String conversationId) { broadcast.set(true); }
            @Override public void onInterruptReceived(java.util.function.Consumer<String> handler) { }
        };
        PersistentCancellationPort port = new PersistentCancellationPort(repository, broadcaster);
        port.requestCancellation(taskId, CancellationReason.USER_REQUESTED);
        assertTrue(port.isCancellationRequested(taskId));
        assertTrue(broadcast.get());
    }

    @Test
    void outboxPublisherRepublishesPendingRowsOnce() {
        InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        InMemoryRunEventStore events = new InMemoryRunEventStore();
        AtomicBoolean received = new AtomicBoolean();
        String runId = RunId.newId().value();
        outbox.add("agent_run", runId, "RunCompleted", "{}");
        OutboxPublisher publisher = new OutboxPublisher(outbox, events, ignored -> received.set(true));
        assertEquals(1, publisher.publishPending());
        assertTrue(received.get());
        assertEquals(0, publisher.publishPending());
    }
}
