package com.agenttrail.evaluation;

import com.agenttrail.loop.trace.TraceRecord;
import com.agenttrail.loop.trace.TraceStore;

import java.util.List;

/** Extracts reviewable candidates from successful production traces; never creates a GoldenCase. */
public final class GoldenCaseCandidateExtractor {
    private final TraceStore traceStore;

    public GoldenCaseCandidateExtractor(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    public List<GoldenCaseCandidate> extract(String conversationId) {
        return traceStore.findByConversationId(conversationId).stream()
                .filter(TraceRecord::success)
                .filter(record -> record.inputData() != null && !record.inputData().isBlank())
                .map(record -> new GoldenCaseCandidate(conversationId, record.round(), record.inputData(),
                        record.outputData(), record.recordedAtMillis(), ReviewStatus.PENDING_HUMAN_CONFIRMATION))
                .toList();
    }

    public record GoldenCaseCandidate(String conversationId, int round, String question,
                                      String actualOutput, long recordedAtMillis, ReviewStatus reviewStatus) {
    }

    public enum ReviewStatus {
        PENDING_HUMAN_CONFIRMATION
    }
}
