package com.agenttrail.analytics.golden;

import com.agenttrail.evaluation.GoldenTaskReport;
import com.agenttrail.evaluation.GoldenTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenTaskRunnerTest {
    @Test
    void loadsAllFixturesAndAppliesDeterministicAssertions() {
        var report = GoldenTaskRunner.run(testCase -> {
            String result = switch (testCase.dimension()) {
                case "empty_result" -> "no matching data";
                case "masking" -> "masked output";
                default -> testCase.id().equals("sql-007") ? "truncated" : "query succeeded, 1 row";
            };
            List<String> tools = testCase.expectedToolCalls().isEmpty()
                    ? List.of("execute_sql", "lookup_glossary", "calculate")
                    : testCase.expectedToolCalls();
            return new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), true, "", 1, 4,
                    "SELECT * FROM rental WHERE dept_id IN (3)", result, tools,
                    Map.of("rowCount", 1, "scalar.total", 1, "resultMatchesReference", true));
        });

        assertThat(report.observations()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(report.observations()).allMatch(GoldenTaskReport.GoldenObservation::passed);
    }
}
