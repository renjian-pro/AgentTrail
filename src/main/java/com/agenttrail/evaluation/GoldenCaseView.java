package com.agenttrail.evaluation;

import java.util.List;
import java.util.Map;

/** One row of the case-management table: YAML baseline cases and DB-managed cases in one shape. */
public record GoldenCaseView(String id, String dimension, String question, String asUser,
                             String referenceSql, List<Map<String, Object>> assertions,
                             List<String> expectedToolCalls, String source, String sourceConversationId,
                             boolean editable, long createdAtMillis, long updatedAtMillis) {
    public static final String SOURCE_BUILTIN = "BUILTIN";

    static GoldenCaseView ofBuiltin(GoldenCase golden) {
        return new GoldenCaseView(golden.id(), golden.dimension(), golden.question(), golden.asUser(),
                golden.referenceSql(), golden.assertions(), golden.expectedToolCalls(), SOURCE_BUILTIN, null,
                false, 0, 0);
    }

    static GoldenCaseView ofRecord(GoldenCaseRecord record) {
        return new GoldenCaseView(record.id(), record.dimension(), record.question(), record.asUser(),
                record.referenceSql(), record.assertions(), record.expectedToolCalls(), record.source(),
                record.sourceConversationId(), true, record.createdAtMillis(), record.updatedAtMillis());
    }
}
