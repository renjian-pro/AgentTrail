package com.agenttrail.runtime.lifecycle;

import com.agenttrail.loop.task.InterruptBroadcaster;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.repository.RunRepository;
import com.agenttrail.runtime.task.RunStatus;
import com.agenttrail.runtime.task.TaskRecord;

import java.time.Instant;

public final class PersistentCancellationPort implements CancellationPort {
    private final RunRepository runRepository;
    private final InterruptBroadcaster broadcaster;

    public PersistentCancellationPort(RunRepository runRepository, InterruptBroadcaster broadcaster) {
        this.runRepository = runRepository;
        this.broadcaster = broadcaster;
    }

    @Override
    public void requestCancellation(TaskId taskId, CancellationReason reason) {
        runRepository.find(taskId).ifPresent(record -> {
            runRepository.update(withStatus(record, RunStatus.CANCELLING));
            if (broadcaster != null) {
                broadcaster.broadcastStop(taskId.value());
            }
        });
    }

    @Override
    public boolean isCancellationRequested(TaskId taskId) {
        return runRepository.find(taskId)
                .map(record -> record.status() == RunStatus.CANCELLING || record.status() == RunStatus.CANCELLED)
                .orElse(false);
    }

    private static TaskRecord withStatus(TaskRecord record, RunStatus status) {
        return new TaskRecord(record.taskId(), record.capabilityId(), record.tenantId(), record.userId(),
                record.conversationId(), status, record.currentStage(), record.attempt(), record.leaseOwner(),
                record.leaseUntil(), record.idempotencyKey(), record.checkpointVersion(), record.inputRef(),
                record.outputRef(), record.errorCode(), record.createdAt(), Instant.now());
    }
}
