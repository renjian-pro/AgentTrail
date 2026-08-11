package com.agenttrail.platform.ids;

import java.util.Objects;
import java.util.UUID;

public record ConversationId(String value) {
    public ConversationId {
        Objects.requireNonNull(value, "value");
    }

    public static ConversationId newId() {
        return new ConversationId(UUID.randomUUID().toString());
    }

    public static ConversationId of(String value) {
        return new ConversationId(value);
    }
}
