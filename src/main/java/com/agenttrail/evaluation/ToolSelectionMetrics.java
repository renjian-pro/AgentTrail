package com.agenttrail.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Objective tool-selection metrics for cases that carry expected_tool_calls. */
public record ToolSelectionMetrics(double selectionAccuracy, double unnecessaryCallRate,
                                   int expectedCount, int actualCount) {
    public static ToolSelectionMetrics calculate(List<String> expected, List<String> actual) {
        List<String> expectedSafe = expected == null ? List.of() : expected;
        List<String> actualSafe = actual == null ? List.of() : actual;
        long matched = actualSafe.stream().filter(expectedSafe::contains).distinct().count();
        long unnecessary = actualSafe.stream().filter(call -> !expectedSafe.contains(call)).count();
        double accuracy = expectedSafe.isEmpty() ? 0 : (double) matched / expectedSafe.stream().distinct().count();
        double unnecessaryRate = actualSafe.isEmpty() ? 0 : (double) unnecessary / actualSafe.size();
        return new ToolSelectionMetrics(accuracy, unnecessaryRate, expectedSafe.size(), actualSafe.size());
    }

    public Map<String, Object> asMap() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("toolSelectionAccuracy", selectionAccuracy);
        metrics.put("unnecessaryToolCallRate", unnecessaryCallRate);
        metrics.put("expectedToolCallCount", expectedCount);
        metrics.put("actualToolCallCount", actualCount);
        return metrics;
    }
}
