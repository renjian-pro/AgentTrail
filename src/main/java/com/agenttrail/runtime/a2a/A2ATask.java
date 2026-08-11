package com.agenttrail.runtime.a2a;

import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.task.RunStatus;

public record A2ATask(TaskId taskId, String agentId, RunStatus status) { }
