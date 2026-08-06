package com.agenttrail.capability.deepresearch;

/**
 * 一个分层执行计划里的任务（issue #25/#34）——只包含"要不要调联网搜索工具"的指令，
 * 不做分析/总结这类不调工具的纯文本任务。
 *
 * @param order 所属层，从 1 开始；同一层内的任务并发执行，跨层严格串行，
 *              N 层任务的执行上下文只看 N-1 层的结果（单跳依赖，issue #34 的明确简化）
 */
public record ResearchTask(String id, String instruction, int order) {
}
