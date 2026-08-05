package com.agenttrail.analytics.golden;

import com.agenttrail.evaluation.GoldenCase;
import com.agenttrail.evaluation.GoldenTaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "agenttrail.golden.enabled", matches = "true")
class GoldenTaskIT {
    @Test
    void goldenDatasetHasEnoughCoverage() {
        var cases = GoldenTaskRunner.loadAll();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(20);
        assertThat(cases).extracting(GoldenCase::dimension)
                .contains("sql_correctness", "permission", "masking",
                        "empty_result", "reproducibility", "cost");
        assertThat(cases.stream().filter(item -> "permission".equals(item.dimension())).toList())
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(cases.stream().filter(item -> "masking".equals(item.dimension())).toList())
                .hasSizeGreaterThanOrEqualTo(3);
    }
}
