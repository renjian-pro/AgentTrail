package com.agenttrail.capability.chat.application;

import java.util.Set;

public record ToolScope(boolean webSearch, boolean chart, Set<String> additionalToolNames) {
    public ToolScope {
        additionalToolNames = additionalToolNames == null ? Set.of() : Set.copyOf(additionalToolNames);
    }

    public static ToolScope none() {
        return new ToolScope(false, false, Set.of());
    }

    public static ToolScope webSearchOnly() {
        return new ToolScope(true, false, Set.of());
    }

    public boolean requiresTools() {
        return webSearch || chart || !additionalToolNames.isEmpty();
    }
}
