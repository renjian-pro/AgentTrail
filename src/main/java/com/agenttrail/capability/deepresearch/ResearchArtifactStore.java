package com.agenttrail.capability.deepresearch;

import com.agenttrail.platform.ids.TaskId;
import java.util.Optional;

public interface ResearchArtifactStore {
    void save(TaskId taskId, DeepResearchReport report);
    Optional<DeepResearchReport> find(TaskId taskId);
}
