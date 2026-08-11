package com.agenttrail.platform.identity;

public record Principal(String userId) {
    public static final Principal ANONYMOUS = new Principal(null);
}
