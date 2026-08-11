package com.agenttrail.runtime;

import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.AgentResult;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRuntimePort;

public final class AgentRunCoordinator {
    private final AgentRuntimePort runtime;

    public AgentRunCoordinator(AgentRuntimePort runtime) {
        this.runtime = runtime;
    }

    public AgentRunHandle start(AgentRequest request) {
        return runtime.start(request);
    }

    public AgentResult call(AgentRequest request) {
        return runtime.call(request);
    }
}
