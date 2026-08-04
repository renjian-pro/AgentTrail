package com.agenttrail.analytics.golden;

import java.util.List;
import java.util.Map;

public record GoldenCase(String id,
                         String dimension,
                         String question,
                         String asUser,
                         String referenceSql,
                         Object expected,
                         List<Map<String, Object>> assertions) {
    public GoldenCase {
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }
}
