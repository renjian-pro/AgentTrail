package com.agenttrail.runtime.lifecycle;

import com.agenttrail.loop.task.RedisTaskLock;

import java.time.Duration;

public final class RedisLeaseManager implements LeaseManager {
    private final RedisTaskLock delegate;

    public RedisLeaseManager(RedisTaskLock delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean tryAcquire(String resourceId, Duration ttl) {
        return delegate.tryAcquire(resourceId);
    }

    @Override
    public boolean renew(String resourceId, Duration ttl) {
        return delegate.renew(resourceId);
    }

    @Override
    public boolean release(String resourceId) {
        return delegate.release(resourceId);
    }
}
