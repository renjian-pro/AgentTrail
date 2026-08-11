package com.agenttrail.platform.ids;

import java.util.Objects;
import java.util.UUID;

public record RunId(String value) {
    public RunId {
        Objects.requireNonNull(value, "value");
    }

    public static RunId newId() {
        return new RunId(UUID.randomUUID().toString());
    }

    public static RunId of(String value) {
        return new RunId(value);
    }
}
