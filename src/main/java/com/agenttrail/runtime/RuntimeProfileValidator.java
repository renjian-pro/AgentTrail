package com.agenttrail.runtime;

public final class RuntimeProfileValidator {
    private RuntimeProfileValidator() {
    }

    public static RuntimeProfile validate(RuntimeProfile profile) {
        if (profile == null) {
            throw new IllegalStateException("RuntimeProfile must be configured");
        }
        if (profile.contextCompaction().policy() == null) {
            throw new IllegalStateException("Context compaction is enabled without a policy");
        }
        RuntimeModule.PauseResume pause = profile.pauseResume();
        if (pause.enabled() && pause.config().store() == null) {
            throw new IllegalStateException("Pause/resume is enabled without a PauseStateStore");
        }
        if (profile.stageOutput().manager() == null) {
            throw new IllegalStateException("Stage output is enabled without a manager");
        }
        return profile;
    }
}
