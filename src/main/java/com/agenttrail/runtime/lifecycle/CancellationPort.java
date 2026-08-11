package com.agenttrail.runtime.lifecycle;

import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.api.CancellationReason;

public interface CancellationPort {
    void requestCancellation(TaskId taskId, CancellationReason reason);

    boolean isCancellationRequested(TaskId taskId);
}
