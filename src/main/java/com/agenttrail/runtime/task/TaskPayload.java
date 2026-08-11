package com.agenttrail.runtime.task;

import java.util.Map;

public record TaskPayload(Map<String, Object> values) {
    public TaskPayload {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static TaskPayload empty() {
        return new TaskPayload(Map.of());
    }
}
