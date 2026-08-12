package com.agenttrail.runtime.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local lease used when Redis is not configured and by deterministic unit tests. */
public final class InMemoryLeaseManager implements LeaseManager {
    private static final Map<String, Lease> GLOBAL_LEASES = new ConcurrentHashMap<>();
    private final String owner = UUID.randomUUID().toString();

    @Override
    public boolean tryAcquire(String resourceId, Duration ttl) {
        requireTtl(ttl);
        Instant now = Instant.now();
        Lease candidate = new Lease(owner, now.plus(ttl));
        return GLOBAL_LEASES.compute(resourceId, (ignored, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) return candidate;
            return current;
        }) == candidate;
    }

    @Override
    public boolean renew(String resourceId, Duration ttl) {
        requireTtl(ttl);
        Instant now = Instant.now();
        boolean[] renewed = {false};
        GLOBAL_LEASES.computeIfPresent(resourceId, (ignored, current) -> {
            if (owner.equals(current.owner()) && current.expiresAt().isAfter(now)) {
                renewed[0] = true;
                return new Lease(owner, now.plus(ttl));
            }
            return current;
        });
        return renewed[0];
    }

    @Override
    public boolean release(String resourceId) {
        boolean[] released = {false};
        GLOBAL_LEASES.computeIfPresent(resourceId, (ignored, current) -> {
            if (owner.equals(current.owner())) {
                released[0] = true;
                return null;
            }
            return current;
        });
        return released[0];
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("lease ttl must be positive");
        }
    }

    private record Lease(String owner, Instant expiresAt) { }
}
