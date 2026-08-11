package com.agenttrail.capability.deepresearch;

import java.util.List;

public record DeepResearchState(String question, String clarifyingQuestion, String topic, int round,
                                String previousFeedback, List<TaskResult> allResults,
                                List<DeepResearchContextEntry> researchContext, String report) {
    public DeepResearchState {
        allResults = allResults == null ? List.of() : List.copyOf(allResults);
        researchContext = researchContext == null ? List.of() : List.copyOf(researchContext);
    }
}
