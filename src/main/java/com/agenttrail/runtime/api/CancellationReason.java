package com.agenttrail.runtime.api;

public record CancellationReason(String description) {
    public static final CancellationReason USER_REQUESTED =
            new CancellationReason("user_requested");
}
