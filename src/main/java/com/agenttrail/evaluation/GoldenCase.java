package com.agenttrail.evaluation;

import java.util.List;
import java.util.Map;

/** A human-curated evaluation case. Trace-derived candidates are deliberately not this type. */
public record GoldenCase(String id,
                         String dimension,
                         String question,
                         String asUser,
                         String referenceSql,
                         Object expected,
                         List<Map<String, Object>> assertions,
                         List<String> expectedToolCalls) {

    public GoldenCase {
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        expectedToolCalls = expectedToolCalls == null ? List.of() : List.copyOf(expectedToolCalls);
    }

    /** Compatibility constructor for existing fixtures and test harnesses. */
    public GoldenCase(String id, String dimension, String question, String asUser,
                      String referenceSql, Object expected, List<Map<String, Object>> assertions) {
        this(id, dimension, question, asUser, referenceSql, expected, assertions, List.of());
    }
}
