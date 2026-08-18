package com.agenttrail.web.dto;

import com.agenttrail.capability.chat.application.PausedRunPort;
import com.agenttrail.platform.tools.PendingToolView;
import com.agenttrail.platform.tools.ResumeSafePoint;

import java.util.List;

public record PendingApprovalResponse(
        String conversationId,
        String reason,
        long pausedAtMillis,
        List<PendingToolView> pendingTools,
        ResumeSafePoint safePoint) {

    public static PendingApprovalResponse from(PausedRunPort.PausedRun paused) {
        return new PendingApprovalResponse(paused.conversationId(), paused.reason(), paused.pausedAtMillis(),
                paused.pendingTools(), paused.safePoint());
    }
}
