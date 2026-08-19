package com.agenttrail.web.service;

import com.agenttrail.capability.ppt.PptCheckpointEvent;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.capability.ppt.PptRunStatus;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PptTaskViewAssemblerTest {

    @Test
    void exposesTheWholePipelineAsStageEventsAndImageCountsAsDetailEvents() {
        PptGenerationService generation = mock(PptGenerationService.class);
        PptTask task = new PptTask(7L, "u-1", "c-1", PptState.IMAGE, PptRunStatus.RUNNING,
                null, "{}", 1, 8, null, null, 1, 0, 10, 20);
        PptGenerationContext context = PptGenerationContext.initial("c-1", "AI Agent 技术原理");
        when(generation.describe("u-1", 7L)).thenReturn(Optional.of(task));
        when(generation.contextOf(task)).thenReturn(context);
        when(generation.pendingClarifyingQuestionOf("u-1", 7L)).thenReturn(null);
        when(generation.eventsOf(7L)).thenReturn(List.of(
                event(PptState.REQUIREMENT, PptCheckpointEvent.OUTCOME_STARTED, null, 1, 0),
                event(PptState.REQUIREMENT, PptCheckpointEvent.OUTCOME_SUCCEEDED, null, 1, 1),
                event(PptState.IMAGE, PptCheckpointEvent.OUTCOME_STARTED, null, 2, 0),
                event(PptState.IMAGE, PptCheckpointEvent.OUTCOME_PROGRESS,
                        "图片生成完成（3/6）", 2, 2)));

        var response = new PptTaskViewAssembler(generation).response("u-1", 7L);

        assertThat(response.taskView().progressEvents()).extracting(event -> event.level())
                .containsExactly("STAGE", "STAGE", "STAGE", "DETAIL");
        assertThat(response.taskView().progressEvents().get(3).current()).isEqualTo(3);
        assertThat(response.taskView().progressEvents().get(3).total()).isEqualTo(6);
        assertThat(response.taskView().completedStages()).contains(PptState.REQUIREMENT);
        assertThat(response.taskView().currentStageLabel()).isEqualTo("生成图片素材");
    }

    private static PptCheckpointEvent event(PptState state, String outcome, String output,
            long before, long after) {
        return new PptCheckpointEvent(7L, state, 1, 100 + before, after == 0 ? 0 : 100 + after,
                outcome, null, output, null, null, null, "worker", null, before, after);
    }
}
