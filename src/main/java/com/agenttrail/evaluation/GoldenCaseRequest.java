package com.agenttrail.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Create/update payload for a DB-managed Golden Case. {@code source}/{@code sourceConversationId} are only
 * meaningful on create — the candidate-promotion flow posts {@code PROMOTED} + the originating conversation id,
 * a plain "new case" form leaves both null and the service defaults to {@code MANUAL}.
 */
public record GoldenCaseRequest(String id, String dimension, String question, String asUser,
                                String referenceSql, List<Map<String, Object>> assertions,
                                List<String> expectedToolCalls, String source, String sourceConversationId) {
}
