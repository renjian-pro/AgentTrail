package com.agenttrail.evaluation;

import java.util.List;

/** Data-only report for running the same Golden cases under two context policies. */
public record CompressionTradeoffReport(List<PolicyMeasurement> measurements) {
    public CompressionTradeoffReport {
        measurements = measurements == null ? List.of() : List.copyOf(measurements);
    }

    public String markdown() {
        StringBuilder result = new StringBuilder("# Context compression trade-off\n\n")
                .append("| policy | passed | total | pass rate | median latency ms |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (PolicyMeasurement measurement : measurements) {
            result.append("| ").append(measurement.policy()).append(" | ")
                    .append(measurement.passed()).append(" | ").append(measurement.total())
                    .append(" | ").append(measurement.passRate()).append(" | ")
                    .append(measurement.medianLatencyMillis()).append(" |\n");
        }
        return result.toString();
    }

    public record PolicyMeasurement(String policy, long passed, long total,
                                    double passRate, long medianLatencyMillis) { }
}
