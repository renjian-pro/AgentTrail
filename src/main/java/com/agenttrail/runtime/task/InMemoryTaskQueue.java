package com.agenttrail.runtime.task;

import com.agenttrail.platform.ids.TaskId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

public final class InMemoryTaskQueue implements TaskQueue {
    private final Queue<Entry> ready = new ArrayDeque<>();
    private final Map<TaskId, Entry> inFlight = new HashMap<>();

    @Override
    public synchronized void enqueue(TaskId taskId, TaskPayload payload) {
        ready.add(new Entry(taskId, payload, Instant.now()));
    }

    @Override
    public synchronized Optional<QueuedTask> poll(String workerId, Duration visibilityTimeout) {
        requeueExpired();
        Instant now = Instant.now();
        Entry entry = ready.peek();
        if (entry == null || entry.availableAt().isAfter(now)) {
            return Optional.empty();
        }
        ready.remove();
        Entry claimed = entry.withVisibility(now.plus(visibilityTimeout), workerId);
        inFlight.put(claimed.taskId(), claimed);
        return Optional.of(new QueuedTask(claimed.taskId(), claimed.payload(), workerId, claimed.visibleUntil()));
    }

    @Override
    public synchronized void ack(TaskId taskId) {
        inFlight.remove(taskId);
    }

    @Override
    public synchronized void nack(TaskId taskId, Duration retryDelay) {
        Entry entry = inFlight.remove(taskId);
        if (entry != null) {
            ready.add(entry.withAvailability(Instant.now().plus(retryDelay)));
        }
    }

    private void requeueExpired() {
        Instant now = Instant.now();
        inFlight.values().removeIf(entry -> {
            if (entry.visibleUntil() != null && !entry.visibleUntil().isAfter(now)) {
                ready.add(entry.withAvailability(now));
                return true;
            }
            return false;
        });
    }

    private record Entry(TaskId taskId, TaskPayload payload, Instant availableAt,
                         String workerId, Instant visibleUntil) {
        private Entry(TaskId taskId, TaskPayload payload, Instant availableAt) {
            this(taskId, payload, availableAt, null, null);
        }

        private Entry withVisibility(Instant until, String worker) {
            return new Entry(taskId, payload, availableAt, worker, until);
        }

        private Entry withAvailability(Instant time) {
            return new Entry(taskId, payload, time, null, null);
        }
    }
}
