package com.agenttrail.runtime.coordinator;

import com.agenttrail.platform.ids.CapabilityId;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.repository.RunRepository;
import com.agenttrail.runtime.task.RunStatus;
import com.agenttrail.runtime.task.TaskPayload;

import java.util.Optional;

public interface TaskCoordinator {
    TaskId submit(CapabilityId capabilityId, TaskPayload payload, String idempotencyKey);

    Optional<RunStatus> query(TaskId taskId);

    void cancel(TaskId taskId, CancellationReason reason);

    TaskId resume(TaskId taskId);
}
