package com.agenttrail.runtime.repository;

import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.RunId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryRunEventStore implements RunEventStore {
    private final Map<RunId, List<EventEnvelope>> events = new ConcurrentHashMap<>();
    private final Map<RunId, AtomicLong> sequences = new ConcurrentHashMap<>();

    @Override
    public EventEnvelope append(EventEnvelope event) {
        long sequence = sequences.computeIfAbsent(event.runId(), ignored -> new AtomicLong()).incrementAndGet();
        EventEnvelope stored = new EventEnvelope(event.eventId(), event.runId(), event.taskId(),
                event.conversationId(), sequence, event.occurredAt(), event.type(), event.source(),
                event.visibility(), event.payload());
        events.computeIfAbsent(event.runId(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(stored);
        return stored;
    }

    @Override
    public List<EventEnvelope> afterSequence(RunId runId, long sequence) {
        List<EventEnvelope> stream = events.getOrDefault(runId, List.of());
        synchronized (stream) {
            return stream.stream().filter(event -> event.sequence() > sequence).toList();
        }
    }
}
