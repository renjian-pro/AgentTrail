package com.agenttrail.capability.analytics.glossary;

import java.util.List;

public record GlossaryEntry(String term,
                            List<String> synonyms,
                            String description,
                            String sqlFragment,
                            String example) {
    public GlossaryEntry {
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        description = description == null ? "" : description;
        sqlFragment = sqlFragment == null ? "" : sqlFragment;
        example = example == null ? "" : example;
    }
}
