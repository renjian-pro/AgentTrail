package com.agenttrail.loop.deepresearch;

/**
 * 一个研究任务的 ReAct 子循环执行结果（issue #25/#36）。
 *
 * @param output       成功时的输出；失败时为 null
 * @param success       是否成功——耗尽重试次数后仍失败时为 false，此时不阻塞其余任务/其余层继续执行
 * @param errorMessage  失败时的最后一次错误信息；成功时为 null
 */
public record TaskResult(String taskId, String instruction, String output, boolean success, String errorMessage) {

    public static TaskResult success(String taskId, String instruction, String output) {
        return new TaskResult(taskId, instruction, output, true, null);
    }

    public static TaskResult failure(String taskId, String instruction, String errorMessage) {
        return new TaskResult(taskId, instruction, null, false, errorMessage);
    }
}
