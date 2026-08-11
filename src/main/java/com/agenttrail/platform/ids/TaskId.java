package com.agenttrail.platform.ids;

import java.util.Objects;
import java.util.UUID;

public record TaskId(String value) {
    public TaskId {
        Objects.requireNonNull(value, "value");
    }

    public static TaskId newId() {
        return new TaskId(UUID.randomUUID().toString());
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }
}
