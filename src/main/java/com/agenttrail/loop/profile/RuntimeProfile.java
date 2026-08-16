package com.agenttrail.loop.profile;

import java.util.Objects;

public record RuntimeProfile(
        RuntimeModule.ContextCompaction contextCompaction,
        RuntimeModule.Memory memory,
        RuntimeModule.PauseResume pauseResume,
        RuntimeModule.Trace trace,
        RuntimeModule.StageOutput stageOutput,
        RuntimeModule.ToolSearch toolSearch) {

    public RuntimeProfile {
        Objects.requireNonNull(contextCompaction, "contextCompaction");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(pauseResume, "pauseResume");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(stageOutput, "stageOutput");
        Objects.requireNonNull(toolSearch, "toolSearch");
    }

    public static RuntimeProfile defaults() {
        return new RuntimeProfile(
                RuntimeModule.contextCompaction(com.agenttrail.loop.context.ContextPolicy.defaults()),
                RuntimeModule.memory(null),
                RuntimeModule.PauseResume.DISABLED,
                RuntimeModule.trace(null),
                RuntimeModule.stageOutput(com.agenttrail.loop.stageoutput.StageOutputManager.EMPTY),
                RuntimeModule.toolSearch(null));
    }

    public String summary(String profileId, String modelId, String tools, String persistence) {
        return "profile=" + profileId + " model=" + modelId + " tools=" + tools
                + " pause=" + pauseResume.enabled() + " memory=" + (memory.store() != null)
                + " trace=" + (trace.store() != null) + " persistence=" + persistence;
    }
}
