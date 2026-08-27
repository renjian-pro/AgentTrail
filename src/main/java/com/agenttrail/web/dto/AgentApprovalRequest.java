package com.agenttrail.web.dto;

/** 普通对话 HITL 审批结果；恢复所需的模型和工具范围以服务端暂停快照为准。 */
public record AgentApprovalRequest(
        boolean approved,
        String rejectionReason) {
}
