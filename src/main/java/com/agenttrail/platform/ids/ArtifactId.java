package com.agenttrail.platform.ids;

import java.util.Objects;
import java.util.UUID;

public record ArtifactId(String value) {
    public ArtifactId {
        Objects.requireNonNull(value, "value");
    }

    public static ArtifactId newId() {
        return new ArtifactId(UUID.randomUUID().toString());
    }

    public static ArtifactId of(String value) {
        return new ArtifactId(value);
    }
}
