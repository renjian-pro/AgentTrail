package com.agenttrail.capability.ppt;

/**
 * PPT 任务的运行生命周期，与 {@link PptState} 表示的业务 checkpoint 分离。
 *
 * <p>同一个任务失败时仍保留真实的 {@code PptState}，前端和恢复逻辑通过这里判断任务是
 * 正在运行、等待用户、可重试还是已经进入终态，避免再用 errorMsg 猜测生命周期。
 */
public enum PptRunStatus {
    QUEUED,
    RUNNING,
    WAITING_INPUT,
    RETRY_WAIT,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    SUCCEEDED
}
