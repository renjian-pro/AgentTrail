package com.agenttrail.runtime.outbox;

import java.util.List;

public interface OutboxStore {
    OutboxRecord add(String aggregateType, String aggregateId, String eventType, String payload);

    List<OutboxRecord> pending();

    void update(OutboxRecord record);
}
