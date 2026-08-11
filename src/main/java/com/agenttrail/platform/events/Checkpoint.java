package com.agenttrail.platform.events;

import com.agenttrail.platform.ids.TaskId;

import java.time.Instant;

public record Checkpoint(TaskId taskId, long version, String stage,
                         String stateSnapshot, Instant createdAt) {
}
