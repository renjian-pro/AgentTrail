package com.agenttrail.web;

/** 普通对话 HITL 审批结果；model/mode 用于恢复时选择和原请求一致的执行器。 */
public record AgentApprovalRequest(
        boolean approved,
        String rejectionReason,
        String modelId,
        boolean webSearchEnabled,
        String mode) {
}
