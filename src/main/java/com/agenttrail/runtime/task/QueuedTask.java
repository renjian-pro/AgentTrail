package com.agenttrail.runtime.task;

import com.agenttrail.platform.ids.TaskId;

import java.time.Instant;

public record QueuedTask(TaskId taskId, TaskPayload payload, String workerId, Instant visibleUntil) {
}
