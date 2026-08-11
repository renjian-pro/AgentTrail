package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.RunId;

public interface AgentRuntimePort {
    AgentRunHandle start(AgentRequest request);

    AgentResult call(AgentRequest request);

    AgentRunSnapshot snapshot(RunId runId);

    void cancel(RunId runId, CancellationReason reason);

    AgentRunHandle resume(RunId runId, ResumeCommand command);
}
