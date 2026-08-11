package com.agenttrail.capability.chat.application;

import com.agenttrail.runtime.api.AgentRuntimePort;

import java.util.Map;
import java.util.Objects;

public final class RuntimeProfileRegistry {
    private final Map<String, AgentRuntimePort> runtimes;
    private final String defaultModelId;
    private final String toolCallingFallbackModelId;

    public RuntimeProfileRegistry(Map<String, AgentRuntimePort> runtimes, String defaultModelId,
                                  String toolCallingFallbackModelId) {
        this.runtimes = Map.copyOf(runtimes);
        this.defaultModelId = defaultModelId;
        this.toolCallingFallbackModelId = toolCallingFallbackModelId;
    }

    public RuntimeProfileRegistry(Map<String, AgentRuntimePort> runtimes, String defaultModelId) {
        this(runtimes, defaultModelId, "deepseek-chat");
    }

    public AgentRuntimePort resolve(String profileId, String modelId, ToolScope scope) {
        String requested = modelId == null || modelId.isBlank() ? defaultModelId : modelId;
        if (scope != null && scope.requiresTools() && "qwen-plus".equals(requested)
                && runtimes.containsKey(toolCallingFallbackModelId)) {
            requested = toolCallingFallbackModelId;
        }
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
