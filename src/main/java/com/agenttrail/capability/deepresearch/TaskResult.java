package com.agenttrail.capability.deepresearch;

/**
 * 一个研究任务的 ReAct 子循环执行结果（issue #25/#34/#36）。
 *
 * @param order         这个任务所属的分层（issue #34）——同 order 并发执行、不同 order 严格串行，
 *                       保留下来是为了让调用方（比如测试、前端展示）能看出一份报告的任务结果
 *                       实际跨了几层，不用反过来从执行日志里猜
 * @param output        成功时的输出；失败时为 null
 * @param success       是否成功——耗尽重试次数后仍失败时为 false，此时不阻塞其余任务/其余层继续执行
 * @param errorMessage  失败时的最后一次错误信息；成功时为 null
 */
public record TaskResult(String taskId, String instruction, int order, String output, boolean success,
        String errorMessage) {

    public static TaskResult success(String taskId, String instruction, int order, String output) {
        return new TaskResult(taskId, instruction, order, output, true, null);
    }

    public static TaskResult failure(String taskId, String instruction, int order, String errorMessage) {
        return new TaskResult(taskId, instruction, order, null, false, errorMessage);
    }
}
