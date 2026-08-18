package com.agenttrail.web.dto;

import com.agenttrail.capability.ppt.PptArtifactRef;
import com.agenttrail.capability.ppt.PptFailure;
import com.agenttrail.capability.ppt.PptRunStatus;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptWarning;

import java.util.List;

/**
 * 所有 PPT 操作共享的统一任务视图。它是后端权威状态的只读投影，前端卡片/历史/恢复入口都消费它。
 */
public record PptTaskView(long taskId, String conversationId, String operation, PptState pipelineState,
        PptRunStatus runStatus, long revision, String currentStageLabel, List<PptState> completedStages,
        int progressPercent, String clarification, PptFailure failure, List<PptWarning> warnings,
        PptArtifactRef artifact, Long baseTaskId, String baseArtifactId, long createdAtMillis,
        long updatedAtMillis, PptTaskCapabilities capabilities) {
    public PptTaskView {
        completedStages = completedStages == null ? List.of() : List.copyOf(completedStages);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("PPT progress percent must be between 0 and 100");
        }
    }
}
