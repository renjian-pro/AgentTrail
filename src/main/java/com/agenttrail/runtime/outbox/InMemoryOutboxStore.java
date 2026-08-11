package com.agenttrail.runtime.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryOutboxStore implements OutboxStore {
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, OutboxRecord> records = new ConcurrentHashMap<>();

    @Override
    public OutboxRecord add(String aggregateType, String aggregateId, String eventType, String payload) {
        long id = ids.incrementAndGet();
        OutboxRecord record = new OutboxRecord(id, aggregateType, aggregateId, eventType, payload,
                OutboxRecord.Status.PENDING, 0, Instant.now(), null);
        records.put(id, record);
        return record;
    }

    @Override
    public List<OutboxRecord> pending() {
        return records.values().stream()
                .filter(record -> record.status() == OutboxRecord.Status.PENDING)
                .sorted(java.util.Comparator.comparing(OutboxRecord::createdAt))
                .toList();
    }

    @Override
    public void update(OutboxRecord record) {
        records.put(record.id(), record);
    }
}
