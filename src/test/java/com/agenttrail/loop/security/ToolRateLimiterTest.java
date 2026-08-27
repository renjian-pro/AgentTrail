package com.agenttrail.loop.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 没配 Redis 时的降级行为——真实的限速语义（真的会拒绝超限调用）需要一个真实 Redis 服务端才能
 * 验证到位，见 {@link ToolRateLimiterIT}。这里只测"拿不到 RedissonClient 时永远放行"这条不依赖
 * 外部服务的分支，沿用 {@code RedisConfig}/{@code AgentLoopExecutorConfig.agentTaskManager} 已经
 * 验证过的"可选机制，没配就永远不生效"降级方式。
 */
class ToolRateLimiterTest {

    @Test
    void allowsEveryCallWhenRedisIsNotConfigured() {
        ToolRateLimiter limiter = new ToolRateLimiter(null, 1, Duration.ofMinutes(1));

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.allow("conv-1")).isTrue();
        }
    }
}
