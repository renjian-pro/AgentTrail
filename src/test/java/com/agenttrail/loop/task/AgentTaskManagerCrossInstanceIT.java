package com.agenttrail.loop.task;

import com.agenttrail.loop.model.AgentStreamEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #12 的核心场景：会话实际跑在另一个实例上，本地 map 找不到，只能靠 Redis 广播兜底。
 * 用两个各自独立的 {@link RedissonClient}（各开一条连接，模拟两个真正分开的应用进程）+
 * 两个独立的 {@link AgentTaskManager}，而不是共用一个对象——用同一个 JVM 对象共享，
 * 测出来的只是"这段代码逻辑对不对"，测不出"跨网络的 Pub/Sub 真的能把消息从一个连接
 * 送到另一个连接"这件事本身。
 */
@Testcontainers
class AgentTaskManagerCrossInstanceIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static RedissonClient redissonForInstanceA;
    private static RedissonClient redissonForInstanceB;

    private RedisInterruptBroadcaster broadcasterA;
    private RedisInterruptBroadcaster broadcasterB;

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

    @AfterEach
    void closeBroadcasters() {
        if (broadcasterA != null) {
            broadcasterA.close();
        }
        if (broadcasterB != null) {
            broadcasterB.close();
        }
    }

    /**
     * A 实例真正持有并运行着这个会话；B 实例收到"停止 conv-X"的请求，本地完全没有这个任务，
     * 只能广播出去——最终必须是 A 实例上的订阅被取消，而 B 从头到尾都不知道任务的任何细节。
     */
    @Test
    void stopRequestOnOneInstanceInterruptsTheTaskActuallyRunningOnAnother() {
        broadcasterA = new RedisInterruptBroadcaster(redissonForInstanceA);
        broadcasterB = new RedisInterruptBroadcaster(redissonForInstanceB);
        AgentTaskManager instanceA = new AgentTaskManager(broadcasterA);
        AgentTaskManager instanceB = new AgentTaskManager(broadcasterB);

        String conversationId = "conv-" + UUID.randomUUID();
        Sinks.Many<AgentStreamEvent> sinkOnA = Sinks.many().unicast().onBackpressureBuffer();
        instanceA.registerTask(conversationId, sinkOnA);
        Disposable subscriptionOnA = Flux.interval(Duration.ofMillis(10)).subscribe();
        instanceA.setDisposable(conversationId, subscriptionOnA);

        // B 上发起停止请求：B 的本地 map 里根本没有这个会话
        boolean stoppedLocallyOnB = instanceB.stopTask(conversationId);

        assertThat(stoppedLocallyOnB).as("B 自己没有这个任务，不能谎称停掉了").isFalse();
        // Pub/Sub 跨网络投递，等它真正生效——不是本地方法调用那种立刻可见
        Timing.waitUntil(Duration.ofSeconds(5), () -> subscriptionOnA.isDisposed());
        assertThat(instanceA.hasRunningTask(conversationId))
                .as("A 收到广播后必须把自己手上的任务真正清理掉").isFalse();
    }

    /** 反过来，会话本来就在本地：不该指望广播，本地快路径必须立刻生效，不用等 Pub/Sub 往返。 */
    @Test
    void stopRequestForATaskRunningLocallySucceedsWithoutWaitingForTheBroadcastRoundTrip() {
        broadcasterA = new RedisInterruptBroadcaster(redissonForInstanceA);
        AgentTaskManager instanceA = new AgentTaskManager(broadcasterA);

        String conversationId = "conv-" + UUID.randomUUID();
        instanceA.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());
        Disposable subscription = Flux.interval(Duration.ofMillis(10)).subscribe();
        instanceA.setDisposable(conversationId, subscription);

        boolean stopped = instanceA.stopTask(conversationId);

        assertThat(stopped).isTrue();
        assertThat(subscription.isDisposed()).isTrue();
    }

    /** 谁都没有这个会话时，广播照发不误，但没有任何一个实例应该受影响——不能凭空触发点什么。 */
    @Test
    void broadcastingForAConversationNoInstanceHoldsAffectsNobody() {
        broadcasterA = new RedisInterruptBroadcaster(redissonForInstanceA);
        broadcasterB = new RedisInterruptBroadcaster(redissonForInstanceB);
        AgentTaskManager instanceA = new AgentTaskManager(broadcasterA);
        AgentTaskManager instanceB = new AgentTaskManager(broadcasterB);
        String conversationId = "conv-" + UUID.randomUUID();

        boolean stoppedOnB = instanceB.stopTask(conversationId);

        assertThat(stoppedOnB).isFalse();
        // 给广播一点时间飞过去，确认 A 也确实什么都没发生（不是恰好还没到而已）
        Timing.sleep(Duration.ofMillis(500));
        assertThat(instanceA.hasRunningTask(conversationId)).isFalse();
    }

    // ==================== 跨实例单飞注册（issue #11 的锁真正接进了 registerTask） ====================

    /**
     * 核心场景：A 已经真正抢到了这个会话的跨实例归属，B 完全不知道（B 的本地 map 里没有它），
     * B 仍然不能注册成功——否则同一个会话会在两台机器上同时跑起来，这正是 RedisTaskLock
     * 存在的意义，如果 registerTask 不接它就形同虚设。
     */
    @Test
    void registrationOnASecondInstanceIsRejectedWhileTheFirstInstanceHoldsTheConversation() {
        RedisTaskLock lockA = new RedisTaskLock(redissonForInstanceA, "instance-a", Duration.ofSeconds(30));
        RedisTaskLock lockB = new RedisTaskLock(redissonForInstanceB, "instance-b", Duration.ofSeconds(30));
        AgentTaskManager instanceA = new AgentTaskManager(lockA, null);
        AgentTaskManager instanceB = new AgentTaskManager(lockB, null);
        String conversationId = "conv-" + UUID.randomUUID();

        boolean registeredOnA = instanceA.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());
        boolean registeredOnB = instanceB.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());

        assertThat(registeredOnA).isTrue();
        assertThat(registeredOnB).as("A 已经真正持有这个会话，B 不能注册成功").isFalse();
        // B 的本地占位必须已经撤回——不能占着位置却又不是真的在跑
        assertThat(instanceB.hasRunningTask(conversationId)).isFalse();
    }

    /** A 停止/跑完之后主动释放了跨实例归属，B 应该能立刻抢到，不用等 TTL 过期。 */
    @Test
    void afterTheFirstInstanceStopsTheConversationASecondInstanceCanRegisterItImmediately() {
        RedisTaskLock lockA = new RedisTaskLock(redissonForInstanceA, "instance-a", Duration.ofSeconds(30));
        RedisTaskLock lockB = new RedisTaskLock(redissonForInstanceB, "instance-b", Duration.ofSeconds(30));
        AgentTaskManager instanceA = new AgentTaskManager(lockA, null);
        AgentTaskManager instanceB = new AgentTaskManager(lockB, null);
        String conversationId = "conv-" + UUID.randomUUID();
        instanceA.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());

        instanceA.stopTask(conversationId);
        boolean registeredOnB = instanceB.registerTask(conversationId, Sinks.many().unicast().onBackpressureBuffer());

        assertThat(registeredOnB).as("A 停止时必须主动释放跨实例归属，不能让 B 干等 TTL").isTrue();
    }

    private static RedissonClient newClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getFirstMappedPort()));
        return Redisson.create(config);
    }
}
