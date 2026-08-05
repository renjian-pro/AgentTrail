package com.agenttrail.evaluation;

import java.util.Map;

/**
 * {@code startedAt} 用 epoch 毫秒而不是 {@link java.time.Instant}——这个应用的 JSON 序列化用的是
 * {@code AgentLoopExecutorConfig.objectMapper()} 里手动 {@code new} 出来的 {@code ObjectMapper}，
 * 没注册 JSR-310 模块，{@code Instant} 字段序列化会直接抛异常（把 {@code /agent/v1/evaluation/history}
 * 端点炸成 500）。仓库里其它落库记录（{@code TraceRecord.recordedAtMillis}、{@code
 * TurnRecord}）也一律用 {@code long ...Millis}，这里跟已有约定对齐，不是新引入一套时间类型。
 */
public record GoldenEvaluationHistoryItem(String taskId, String status, long startedAtMillis,
                                          int completedCases, int totalCases, double passRate,
                                          Map<String, Double> dimensionPassRates) {
}
