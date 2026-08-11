package com.agenttrail.capability.deepresearch;

import com.agenttrail.platform.ids.TaskId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryResearchArtifactStore implements ResearchArtifactStore {
    private final ConcurrentHashMap<TaskId, DeepResearchReport> reports = new ConcurrentHashMap<>();
    @Override public void save(TaskId taskId, DeepResearchReport report) { reports.put(taskId, report); }
    @Override public Optional<DeepResearchReport> find(TaskId taskId) { return Optional.ofNullable(reports.get(taskId)); }
}
