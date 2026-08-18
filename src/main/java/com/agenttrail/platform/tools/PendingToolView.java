package com.agenttrail.platform.tools;

/**
 * 可跨进程边界展示的待处理工具投影。arguments 必须在构造前完成脱敏，不能放原始快照参数。
 */
public record PendingToolView(
        String toolCallId,
        String toolName,
        String arguments,
        ToolRiskLevel riskLevel) {
}
