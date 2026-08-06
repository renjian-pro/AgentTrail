package com.agenttrail.evaluation;

import java.util.List;
import java.util.Map;

/** One row of {@code golden_case} — the admin-writable counterpart to the read-only YAML baseline. */
public record GoldenCaseRecord(String id, String dimension, String question, String asUser,
                               String referenceSql, List<Map<String, Object>> assertions,
                               List<String> expectedToolCalls, String source, String sourceConversationId,
                               long createdAtMillis, long updatedAtMillis) {
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_PROMOTED = "PROMOTED";

    public GoldenCase toGoldenCase() {
        return new GoldenCase(id, dimension, question, asUser, referenceSql, null, assertions, expectedToolCalls);
    }
}
