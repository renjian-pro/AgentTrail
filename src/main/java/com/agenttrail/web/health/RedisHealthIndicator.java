package com.agenttrail.web.health;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

    private final ObjectProvider<RedissonClient> redissonProvider;

    public RedisHealthIndicator(ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
    }

    @Override
    public Health health() {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            return Health.unknown().withDetail("reason", "Redis 未启用").build();
        }
        try {
            redisson.getBucket("agenttrail:health:probe").isExistsAsync()
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            return Health.up().build();
        } catch (Exception unreachable) {
            return Health.down(unreachable).build();
        }
    }
}
