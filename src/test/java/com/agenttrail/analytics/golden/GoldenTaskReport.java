package com.agenttrail.analytics.golden;

import java.util.List;
import java.util.Map;

public record GoldenTaskReport(List<GoldenObservation> observations) {
    public GoldenTaskReport {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public long passed() {
        return observations.stream().filter(GoldenObservation::passed).count();
    }

    public String markdown() {
        StringBuilder output = new StringBuilder("# Golden Tasks\n\n")
                .append("通过率：").append(passed()).append("/").append(observations.size()).append("\n\n");
        output.append("| id | dimension | passed | rounds | elapsedMs | reason |\n|---|---|---:|---:|---:|---|\n");
        for (GoldenObservation item : observations) {
            output.append("| ").append(item.id()).append(" | ").append(item.dimension())
                    .append(" | ").append(item.passed()).append(" | ").append(item.rounds())
                    .append(" | ").append(item.elapsedMs()).append(" | ")
                    .append(item.reason().replace("|", "\\|")).append(" |\n");
        }
        return output.toString();
    }

    public record GoldenObservation(String id, String dimension, boolean passed,
                                    String reason, int rounds, long elapsedMs,
                                    String actualSql, String actualResult,
                                    List<String> toolCalls, Map<String, Object> metrics) {
        public GoldenObservation {
            reason = reason == null ? "" : reason;
            actualSql = actualSql == null ? "" : actualSql;
            actualResult = actualResult == null ? "" : actualResult;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }
    }
}
