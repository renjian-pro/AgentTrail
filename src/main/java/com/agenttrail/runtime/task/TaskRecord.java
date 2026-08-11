package com.agenttrail.runtime.task;

import com.agenttrail.platform.ids.CapabilityId;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.TaskId;

import java.time.Instant;

public record TaskRecord(TaskId taskId, CapabilityId capabilityId, String tenantId,
                         String userId, ConversationId conversationId, RunStatus status,
                         String currentStage, int attempt, String leaseOwner,
                         Instant leaseUntil, String idempotencyKey, long checkpointVersion,
                         String inputRef, String outputRef, String errorCode,
                         Instant createdAt, Instant updatedAt) {
}
