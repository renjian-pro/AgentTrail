package com.agenttrail.capability.deepresearch;

import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.repository.CheckpointStore;
import com.agenttrail.runtime.repository.RunEventStore;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Concrete, explicit DeepResearch driver; intentionally not a generic workflow engine. */
public final class DeepResearchWorkflow {
    private final DeepResearchService service;
    private final CheckpointStore checkpoints;
    private final ResearchArtifactStore artifacts;
    private final RunEventStore events;

    public DeepResearchWorkflow(DeepResearchService service, CheckpointStore checkpoints,
                                ResearchArtifactStore artifacts, RunEventStore events) {
        this.service = Objects.requireNonNull(service);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.events = Objects.requireNonNull(events);
    }

    public DeepResearchReport run(TaskId taskId, String question, Consumer<EventEnvelope> sink) {
        RunId runId = RunId.of(taskId.value());
        ConversationId conversationId = ConversationId.of("deepresearch:" + taskId.value());
        Consumer<String> onStep = step -> {
            DeepResearchStage stage = stageOf(step);
            checkpoints.save(taskId, stage.name(), new DeepResearchState(question, null, null, 0,
                    null, List.of(), List.of(), null).toString());
            EventEnvelope event = EventEnvelope.create(runId, taskId, conversationId,
                    "ResearchStageStarted", "deepresearch", EventEnvelope.Visibility.CLIENT, stage.name());
            EventEnvelope stored = events.append(event);
            if (sink != null) sink.accept(stored);
        };
        DeepResearchReport report = service.research(question, onStep);
        artifacts.save(taskId, report);
        EventEnvelope completed = EventEnvelope.create(runId, taskId, conversationId,
                report.needsClarification() ? "ResearchClarificationRequired" : "ResearchCompleted",
                "deepresearch", EventEnvelope.Visibility.CLIENT,
                report.needsClarification() ? String.valueOf(report.clarifyingQuestion()) : "REPORT_ARTIFACT");
        EventEnvelope stored = events.append(completed);
        if (sink != null) sink.accept(stored);
        checkpoints.save(taskId, DeepResearchStage.REPORT_ARTIFACT.name(), report.toString());
        return report;
    }

    public DeepResearchReport resume(TaskId taskId, String question, Consumer<EventEnvelope> sink) {
        if (checkpoints.latest(taskId)
                .map(checkpoint -> DeepResearchStage.REPORT_ARTIFACT.name().equals(checkpoint.stage()))
                .orElse(false)) {
            return artifacts.find(taskId).orElseThrow(() ->
                    new IllegalStateException("DeepResearch artifact missing for completed task: " + taskId));
        }
        return run(taskId, question, sink);
    }

    public static DeepResearchStage stageOf(String step) {
        if (step == null) return DeepResearchStage.CLARIFY;
        return switch (step.toUpperCase()) {
            case "CLARIFYING" -> DeepResearchStage.CLARIFY;
            case "PLANNING" -> DeepResearchStage.PLAN;
            case "SEARCHING" -> DeepResearchStage.FAN_OUT;
            case "CRITIQUING" -> DeepResearchStage.CRITIQUE;
            case "SUMMARIZING" -> DeepResearchStage.SYNTHESIZE;
            default -> DeepResearchStage.GENERATE_TOPIC;
        };
    }
}
