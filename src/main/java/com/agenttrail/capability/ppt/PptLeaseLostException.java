package com.agenttrail.capability.ppt;

/** 租约续期失败时抛出；此时 worker 绝不能把自己的上下文提交回数据库。 */
public class PptLeaseLostException extends PptGenerationException {
    public PptLeaseLostException(long taskId) {
        super("PPT 任务执行租约已丢失，拒绝提交 checkpoint: " + taskId);
    }
}
