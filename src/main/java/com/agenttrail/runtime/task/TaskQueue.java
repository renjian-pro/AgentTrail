package com.agenttrail.runtime.task;

import com.agenttrail.platform.ids.TaskId;

import java.time.Duration;
import java.util.Optional;

public interface TaskQueue {
    void enqueue(TaskId taskId, TaskPayload payload);

    Optional<QueuedTask> poll(String workerId, Duration visibilityTimeout);

    void ack(TaskId taskId);

    void nack(TaskId taskId, Duration retryDelay);
}
