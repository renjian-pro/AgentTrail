package com.agenttrail.runtime.outbox;

import java.time.Instant;

public record OutboxRecord(long id, String aggregateType, String aggregateId,
                           String eventType, String payload, Status status,
                           int retryCount, Instant createdAt, Instant publishedAt) {
    public enum Status {
        PENDING,
        PUBLISHED,
        FAILED
    }

    public OutboxRecord pending(long id) {
        return new OutboxRecord(id, aggregateType, aggregateId, eventType, payload,
                Status.PENDING, retryCount, createdAt, null);
    }

    public OutboxRecord published() {
        return new OutboxRecord(id, aggregateType, aggregateId, eventType, payload,
                Status.PUBLISHED, retryCount, createdAt, Instant.now());
    }

    public OutboxRecord failed() {
        return new OutboxRecord(id, aggregateType, aggregateId, eventType, payload,
                retryCount >= 10 ? Status.FAILED : Status.PENDING, retryCount + 1, createdAt, null);
    }
}
