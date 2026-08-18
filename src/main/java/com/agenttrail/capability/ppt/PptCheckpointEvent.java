package com.agenttrail.capability.ppt;

/**
 * PPT 阶段执行的追加式审计事件。
 *
 * <p>最新快照负责恢复，事件负责解释一次任务如何走到当前快照；两者职责不同，事件不能被
 * 后续 checkpoint 覆盖，也不保存模型完整思考链。
 */
public record PptCheckpointEvent(
        long taskId,
        PptState stage,
        int attempt,
        long startedAtMillis,
        long finishedAtMillis,
        String outcome,
        String inputSummary,
        String outputSummary,
        String errorCode,
        String retryClass,
        String warningCode,
        String workerId,
        String promptVersion,
        long revisionBefore,
        long revisionAfter) {

    public static final String OUTCOME_STARTED = "STARTED";
    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_CANCELLED = "CANCELLED";
}
