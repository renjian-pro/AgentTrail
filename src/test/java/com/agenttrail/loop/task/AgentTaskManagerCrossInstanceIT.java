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
        waitUntil(Duration.ofSeconds(5), () -> subscriptionOnA.isDisposed());
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
        sleep(Duration.ofMillis(500));
        assertThat(instanceA.hasRunningTask(conversationId)).isFalse();
    }

    private static RedissonClient newClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getFirstMappedPort()));
        return Redisson.create(config);
    }

    private static void waitUntil(Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("条件在 " + timeout + " 内始终没有满足");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
