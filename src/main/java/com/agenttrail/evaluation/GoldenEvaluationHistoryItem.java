package com.agenttrail.evaluation;

import java.time.Instant;
import java.util.Map;

public record GoldenEvaluationHistoryItem(String taskId, String status, Instant startedAt,
                                          int completedCases, int totalCases, double passRate,
                                          Map<String, Double> dimensionPassRates) {
}
