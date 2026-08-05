package com.agenttrail.evaluation;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Runs fixed cases twice and records numbers; it intentionally does not claim one policy is better. */
public final class CompressionTradeoffRunner {
    private CompressionTradeoffRunner() { }

    public static CompressionTradeoffReport compare(List<GoldenCase> cases,
            Function<GoldenCase, GoldenTaskReport.GoldenObservation> microCompact,
            Function<GoldenCase, GoldenTaskReport.GoldenObservation> autoCompact) {
        return new CompressionTradeoffReport(List.of(measure("micro_compact", cases, microCompact),
                measure("auto_compact", cases, autoCompact)));
    }

    private static CompressionTradeoffReport.PolicyMeasurement measure(String policy, List<GoldenCase> cases,
            Function<GoldenCase, GoldenTaskReport.GoldenObservation> executor) {
        GoldenTaskReport report = GoldenTaskRunner.runCaseList(cases, executor);
        List<Long> latencies = report.observations().stream().map(GoldenTaskReport.GoldenObservation::elapsedMs)
                .sorted().toList();
        long median = latencies.isEmpty() ? 0 : latencies.get((latencies.size() - 1) / 2);
        return new CompressionTradeoffReport.PolicyMeasurement(policy, report.passed(),
                report.observations().size(), report.passRate(), median);
    }
}
