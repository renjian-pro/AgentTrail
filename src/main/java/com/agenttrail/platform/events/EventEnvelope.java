package com.agenttrail.platform.events;

import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.platform.ids.TaskId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(String eventId, RunId runId, TaskId taskId,
                            ConversationId conversationId, long sequence,
                            Instant occurredAt, String type, String source,
                            Visibility visibility, String payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(payload, "payload");
    }

    public static EventEnvelope create(RunId runId, TaskId taskId, ConversationId conversationId,
                                       String type, String source, Visibility visibility, String payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), runId, taskId, conversationId,
                0, Instant.now(), type, source, visibility, payload);
    }

    public enum Visibility {
        CLIENT,
        INTERNAL
    }
}
