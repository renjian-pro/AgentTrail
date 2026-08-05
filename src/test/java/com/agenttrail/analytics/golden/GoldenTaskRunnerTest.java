package com.agenttrail.analytics.golden;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenTaskRunnerTest {
    @Test
    void loadsAllFixturesAndAppliesDeterministicAssertions() {
        var report = GoldenTaskRunner.run(testCase -> {
            String result = testCase.dimension().equals("empty_result")
                    ? "没有匹配数据"
                    : testCase.id().equals("sql-007") ? "截断" : "查询成功，共 1 行。********";
            return new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), true, "", 1, 4,
                    "SELECT * FROM rental WHERE dept_id IN (3)", result,
                    List.of("execute_sql", "lookup_glossary", "calculate"),
                    Map.of("rowCount", 1, "scalar.total", 1, "resultMatchesReference", true));
        });

        assertThat(report.observations()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(report.observations()).allMatch(GoldenTaskReport.GoldenObservation::passed);
    }
}
