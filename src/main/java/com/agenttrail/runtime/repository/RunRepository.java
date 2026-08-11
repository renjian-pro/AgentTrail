package com.agenttrail.runtime.repository;

import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.task.TaskRecord;

import java.util.Optional;

public interface RunRepository {
    void save(TaskRecord record);

    Optional<TaskRecord> find(TaskId taskId);

    Optional<TaskRecord> findByIdempotencyKey(String idempotencyKey);

    void update(TaskRecord record);
}
