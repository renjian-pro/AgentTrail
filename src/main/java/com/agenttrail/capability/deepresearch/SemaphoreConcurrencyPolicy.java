package com.agenttrail.capability.deepresearch;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public final class SemaphoreConcurrencyPolicy implements ConcurrencyPolicy {
    private final int permits;
    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    public SemaphoreConcurrencyPolicy(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be positive");
        this.permits = permits;
    }

    @Override
    public Permit acquire(String tenantId, String capability) throws InterruptedException {
        Semaphore gate = gates.computeIfAbsent(String.valueOf(tenantId) + ":" + capability,
                ignored -> new Semaphore(permits));
        gate.acquire();
        return gate::release;
    }
}
