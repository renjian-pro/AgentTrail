package com.agenttrail.runtime.coordinator;

import com.agenttrail.platform.ids.CapabilityId;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.repository.CheckpointStore;
import com.agenttrail.runtime.repository.RunRepository;
import com.agenttrail.runtime.task.InMemoryTaskQueue;
import com.agenttrail.runtime.task.RunStatus;
import com.agenttrail.runtime.task.TaskPayload;
import com.agenttrail.runtime.task.TaskQueue;
import com.agenttrail.runtime.task.TaskRecord;

import java.time.Instant;

public final class InMemoryTaskCoordinator implements TaskCoordinator {
    private final TaskQueue queue;
    private final RunRepository repository;
    private final CheckpointStore checkpoints;

    public InMemoryTaskCoordinator(RunRepository repository, TaskQueue queue, CheckpointStore checkpoints) {
        this.repository = repository;
        this.queue = queue;
        this.checkpoints = checkpoints;
    }

    public InMemoryTaskCoordinator() {
        this(new com.agenttrail.runtime.repository.InMemoryRunRepository(),
                new InMemoryTaskQueue(), new com.agenttrail.runtime.repository.InMemoryCheckpointStore());
    }

    @Override
    public TaskId submit(CapabilityId capabilityId, TaskPayload payload, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get().taskId();
            }
        }
        TaskId taskId = TaskId.newId();
        Instant now = Instant.now();
        repository.save(new TaskRecord(taskId, capabilityId, "default", "anonymous", null,
                RunStatus.PENDING, null, 0, null, null, idempotencyKey, 0,
                null, null, null, now, now));
        queue.enqueue(taskId, payload);
        return taskId;
    }

    @Override
    public java.util.Optional<RunStatus> query(TaskId taskId) {
        return repository.find(taskId).map(TaskRecord::status);
    }

    @Override
    public void cancel(TaskId taskId, CancellationReason reason) {
        repository.find(taskId).ifPresent(record -> repository.update(withStatus(record, RunStatus.CANCELLED)));
    }

    @Override
    public TaskId resume(TaskId taskId) {
        TaskRecord record = repository.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + taskId));
        checkpoints.latest(taskId).ifPresent(checkpoint -> queue.enqueue(taskId,
                new TaskPayload(java.util.Map.of("stage", checkpoint.stage(), "state", checkpoint.stateSnapshot()))));
        repository.update(withStatus(record, RunStatus.PENDING));
        return taskId;
    }

    private static TaskRecord withStatus(TaskRecord record, RunStatus status) {
        Instant now = Instant.now();
        return new TaskRecord(record.taskId(), record.capabilityId(), record.tenantId(), record.userId(),
                record.conversationId(), status, record.currentStage(), record.attempt(), record.leaseOwner(),
                record.leaseUntil(), record.idempotencyKey(), record.checkpointVersion(), record.inputRef(),
                record.outputRef(), record.errorCode(), record.createdAt(), now);
    }
}
