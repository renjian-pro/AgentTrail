package com.agenttrail.web.config;

import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.task.RedisInterruptBroadcaster;
import com.agenttrail.loop.task.RedisTaskLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Sinks;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 安全审计 2026-08-02 的 P0 回归测试：{@link AgentLoopExecutorConfig#agentTaskManager} 以前不管
 * 有没有配 Redis 都只 {@code new AgentTaskManager()}，多实例部署下同一会话可能被两个实例同时跑。
 * {@link RedisTaskLock}/{@link RedisInterruptBroadcaster} 本身早就有覆盖充分的单测（见
 * {@code AgentTaskManagerCrossInstanceIT}），这里只验证"生产装配这一层真的把它们接上了"这件事本身，
 * 不重复造轮子——如果哪次改动不小心把 {@code agentTaskManager} 的 Bean 方法改回裸
 * {@code new AgentTaskManager()}，这个测试要能抓到。
 */
@Testcontainers
class AgentLoopExecutorConfigRedisWiringIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static RedissonClient redissonForInstanceA;
    private static RedissonClient redissonForInstanceB;

    @BeforeAll
    static void startRedisson() {
        redissonForInstanceA = newClient();
        redissonForInstanceB = newClient();
    }

    @AfterAll
    static void shutdownRedisson() {
        redissonForInstanceA.shutdown();
        redissonForInstanceB.shutdown();
    }

    @Test
    void fallsBackToPureLocalBehaviorWhenNoRedissonClientIsAvailable() {
        AgentLoopExecutorConfig config = new AgentLoopExecutorConfig();
        AgentTaskManager instanceA = config.agentTaskManager(providerReturning(null));
        AgentTaskManager instanceB = config.agentTaskManager(providerReturning(null));
        String conversationId = "conv-" + UUID.randomUUID();

        boolean registeredOnA = instanceA.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());
        boolean registeredOnB = instanceB.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());

        assertThat(registeredOnA).isTrue();
        // 没有 RedissonClient 时行为必须和历史完全一致：两个各自独立的实例互相不知道对方，
        // 谁都能"成功"注册同一个会话——这正是不接 Redis 锁时的已知局限，不是这个测试要防的回归。
        assertThat(registeredOnB).as("没有 Redis 时两个实例互相看不见彼此，这是预期内的已知局限").isTrue();
    }

    @Test
    void enforcesCrossInstanceSingleFlightWhenAConfiguredRedissonClientIsAvailable() {
        AgentLoopExecutorConfig config = new AgentLoopExecutorConfig();
        AgentTaskManager instanceA = config.agentTaskManager(providerReturning(redissonForInstanceA));
        AgentTaskManager instanceB = config.agentTaskManager(providerReturning(redissonForInstanceB));
        String conversationId = "conv-" + UUID.randomUUID();

        boolean registeredOnA = instanceA.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());
        boolean registeredOnB = instanceB.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());

        assertThat(registeredOnA).isTrue();
        assertThat(registeredOnB).as("A 已经通过 Redis 真正持有这个会话，B 不能注册成功——" +
                "这是生产装配真的接上了 RedisTaskLock 才会有的行为").isFalse();
        assertThat(instanceB.hasRunningTask(conversationId)).as("B 的本地占位必须已经撤回").isFalse();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedissonClient> providerReturning(RedissonClient client) {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private static RedissonClient newClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getFirstMappedPort()));
        return Redisson.create(config);
    }
}
