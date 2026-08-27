package com.agenttrail.loop.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实限速行为需要真实 Redis 服务端（滑动窗口的原子性是 Redis 自己的行为，mock 一个客户端测不出
 * 真正的限流效果），沿用 {@code RedisTaskLockIT} 的 Testcontainers 用法（issue #11）。
 */
@Testcontainers
class ToolRateLimiterIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static RedissonClient redisson;

    @BeforeAll
    static void startRedisson() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getFirstMappedPort()));
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void shutdownRedisson() {
        redisson.shutdown();
    }

    @Test
    void allowsCallsUpToTheConfiguredLimitThenRejects() {
        ToolRateLimiter limiter = new ToolRateLimiter(redisson, 3, Duration.ofMinutes(1));
        String conversationId = "conv-" + UUID.randomUUID();

        assertThat(limiter.allow(conversationId)).isTrue();
        assertThat(limiter.allow(conversationId)).isTrue();
        assertThat(limiter.allow(conversationId)).isTrue();
        assertThat(limiter.allow(conversationId)).isFalse();
    }

    @Test
    void tracksDifferentConversationsIndependently() {
        ToolRateLimiter limiter = new ToolRateLimiter(redisson, 1, Duration.ofMinutes(1));
        String conversationA = "conv-" + UUID.randomUUID();
        String conversationB = "conv-" + UUID.randomUUID();

        assertThat(limiter.allow(conversationA)).isTrue();
        assertThat(limiter.allow(conversationA)).isFalse();
        assertThat(limiter.allow(conversationB)).isTrue();
    }
}
