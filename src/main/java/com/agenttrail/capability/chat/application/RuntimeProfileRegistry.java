package com.agenttrail.capability.chat.application;

import com.agenttrail.runtime.api.AgentRuntimePort;

import java.util.Map;

public final class RuntimeProfileRegistry {
    private final Map<String, AgentRuntimePort> runtimes;
    private final String defaultModelId;

    public RuntimeProfileRegistry(Map<String, AgentRuntimePort> runtimes, String defaultModelId) {
        this.runtimes = Map.copyOf(runtimes);
        this.defaultModelId = defaultModelId;
    }

    public AgentRuntimePort resolve(String profileId, String modelId, ToolScope scope) {
        String requested = modelId == null || modelId.isBlank() ? defaultModelId : modelId;
        AgentRuntimePort runtime = runtimes.get(requested);
        if (runtime == null) {
            throw new IllegalStateException("Unknown runtime model: " + requested);
        }
        return runtime;
    }

    public AgentRuntimePort resolve(String profileId, String modelId) {
        return resolve(profileId, modelId, ToolScope.none());
    }
}
