package com.agenttrail.capability.chat.application;

import java.util.List;
import java.util.Optional;

/**
 * 聊天应用层查询暂停运行的只读端口。
 *
 * <p>归属、模型和工具范围都来自服务端暂停快照，不能相信恢复请求重新提交的值。
 */
public interface PausedRunPort {

    PausedRunPort NONE = conversationId -> Optional.empty();

    Optional<PausedRun> find(String conversationId);

    record PausedRun(
            String conversationId,
            String userId,
            String modelId,
            String reason,
            long pausedAtMillis,
            boolean webSearchEnabled,
            boolean analyticsEnabled,
            List<PendingTool> pendingTools) {

        public PausedRun {
            pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
        }
    }

    record PendingTool(String toolCallId, String toolName, String arguments, String riskLevel) {
    }
}
