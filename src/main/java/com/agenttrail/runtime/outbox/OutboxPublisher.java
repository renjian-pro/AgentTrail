package com.agenttrail.runtime.outbox;

import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.repository.RunEventStore;

import java.time.Instant;
import java.util.function.Consumer;

public final class OutboxPublisher {
    private final OutboxStore outbox;
    private final RunEventStore eventStore;
    private final Consumer<EventEnvelope> push;

    public OutboxPublisher(OutboxStore outbox, RunEventStore eventStore, Consumer<EventEnvelope> push) {
        this.outbox = outbox;
        this.eventStore = eventStore;
        this.push = push == null ? ignored -> { } : push;
    }

    public int publishPending() {
        int published = 0;
        for (OutboxRecord record : outbox.pending()) {
            try {
                EventEnvelope event = EventEnvelope.create(RunId.of(record.aggregateId()), null, null,
                        record.eventType(), "outbox-publisher", EventEnvelope.Visibility.CLIENT, record.payload());
                EventEnvelope stored = eventStore.append(event);
                push.accept(stored);
                outbox.update(record.published());
                published++;
            } catch (RuntimeException failure) {
                outbox.update(record.failed());
            }
        }
        return published;
    }
}
