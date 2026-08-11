package com.agenttrail.runtime.repository;

import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.task.TaskRecord;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRunRepository implements RunRepository {
    private final Map<TaskId, TaskRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(TaskRecord record) {
        if (records.putIfAbsent(record.taskId(), record) != null) {
            throw new IllegalStateException("Task already exists: " + record.taskId());
        }
    }

    @Override
    public Optional<TaskRecord> find(TaskId taskId) {
        return Optional.ofNullable(records.get(taskId));
    }

    @Override
    public Optional<TaskRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return records.values().stream()
                .filter(record -> idempotencyKey.equals(record.idempotencyKey()))
                .findFirst();
    }

    @Override
    public void update(TaskRecord record) {
        records.put(record.taskId(), record);
    }
}
