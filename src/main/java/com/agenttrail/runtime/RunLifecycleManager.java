package com.agenttrail.runtime;

import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.CancellationReason;

public final class RunLifecycleManager {
    private final AgentTaskManager taskManager;

    public RunLifecycleManager(AgentTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public boolean cancel(RunId runId, CancellationReason reason) {
        return taskManager.stopTask(runId.value());
    }
}
