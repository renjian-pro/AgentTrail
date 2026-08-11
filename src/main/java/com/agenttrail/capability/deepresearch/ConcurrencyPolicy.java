package com.agenttrail.capability.deepresearch;

public interface ConcurrencyPolicy {
    Permit acquire(String tenantId, String capability) throws InterruptedException;

    interface Permit extends AutoCloseable {
        @Override void close();
    }
}
