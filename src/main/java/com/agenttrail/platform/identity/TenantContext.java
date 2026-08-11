package com.agenttrail.platform.identity;

public record TenantContext(String tenantId) {
    public static final TenantContext DEFAULT = new TenantContext("default");
}
