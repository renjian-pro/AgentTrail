package com.agenttrail.runtime.lifecycle;

import java.time.Duration;

public interface LeaseManager {
    boolean tryAcquire(String resourceId, Duration ttl);

    boolean renew(String resourceId, Duration ttl);

    boolean release(String resourceId);
}
