package com.agenttrail.capability.ppt;

/** 条件 checkpoint 更新未命中，表示任务已被其他 worker 或操作推进。 */
public class PptCheckpointConflictException extends IllegalStateException {

    public PptCheckpointConflictException(long taskId, PptState expectedState, long expectedRevision) {
        super("PPT 任务 checkpoint 已变化，拒绝覆盖: taskId=" + taskId
                + ", expectedState=" + expectedState + ", expectedRevision=" + expectedRevision);
    }
}
