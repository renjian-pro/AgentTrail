package com.agenttrail.capability.chat.application;

public record ExecutionPrincipal(String userId, String tenantId) {
    public static final ExecutionPrincipal LEGACY = new ExecutionPrincipal("legacy", null);
}
