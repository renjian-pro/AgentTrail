package com.agenttrail.platform.ids;

import java.util.Objects;

public record CapabilityId(String value) {
    public CapabilityId {
        Objects.requireNonNull(value, "value");
    }

    public static CapabilityId of(String value) {
        return new CapabilityId(value);
    }
}
