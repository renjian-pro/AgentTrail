package com.agenttrail.runtime.repository;

import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.RunId;

import java.util.List;

public interface RunEventStore {
    EventEnvelope append(EventEnvelope event);

    List<EventEnvelope> afterSequence(RunId runId, long sequence);
}
