package com.agenttrail.runtime.repository;

import com.agenttrail.platform.events.Checkpoint;
import com.agenttrail.platform.ids.TaskId;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryCheckpointStore implements CheckpointStore {
    private final Map<TaskId, Map<Long, Checkpoint>> checkpoints = new ConcurrentHashMap<>();
    private final Map<TaskId, AtomicLong> versions = new ConcurrentHashMap<>();

    @Override
    public Checkpoint save(TaskId taskId, String stage, String stateSnapshot) {
        long version = versions.computeIfAbsent(taskId, ignored -> new AtomicLong()).incrementAndGet();
        Checkpoint checkpoint = new Checkpoint(taskId, version, stage, stateSnapshot, Instant.now());
        checkpoints.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>()).put(version, checkpoint);
        return checkpoint;
    }

    @Override
    public Optional<Checkpoint> latest(TaskId taskId) {
        return Optional.ofNullable(checkpoints.get(taskId)).flatMap(values -> values.values().stream()
                .max(java.util.Comparator.comparingLong(Checkpoint::version)));
    }

    @Override
    public Optional<Checkpoint> find(TaskId taskId, long version) {
        return Optional.ofNullable(checkpoints.get(taskId)).flatMap(values -> Optional.ofNullable(values.get(version)));
    }
}
