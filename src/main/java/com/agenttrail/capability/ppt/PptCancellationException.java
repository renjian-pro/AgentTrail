package com.agenttrail.capability.ppt;

/** 外部调用或阶段边界观察到取消后的控制流异常，不应被记录为普通失败。 */
public final class PptCancellationException extends PptGenerationException {
    public PptCancellationException() {
        super("PPT 任务已请求取消");
    }
}
