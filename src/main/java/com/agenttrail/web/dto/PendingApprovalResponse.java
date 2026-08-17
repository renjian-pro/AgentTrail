package com.agenttrail.web.dto;

import com.agenttrail.capability.chat.application.PausedRunPort;

import java.util.List;

public record PendingApprovalResponse(
        String conversationId,
        String reason,
        long pausedAtMillis,
        List<PendingToolResponse> pendingTools) {

    public static PendingApprovalResponse from(PausedRunPort.PausedRun paused) {
        return new PendingApprovalResponse(paused.conversationId(), paused.reason(), paused.pausedAtMillis(),
                paused.pendingTools().stream().map(PendingToolResponse::from).toList());
    }

    public record PendingToolResponse(String toolCallId, String toolName, String arguments, String riskLevel) {
        private static PendingToolResponse from(PausedRunPort.PendingTool tool) {
            return new PendingToolResponse(tool.toolCallId(), tool.toolName(), tool.arguments(), tool.riskLevel());
        }
    }
}
