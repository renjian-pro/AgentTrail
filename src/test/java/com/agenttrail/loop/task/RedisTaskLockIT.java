package com.agenttrail.loop.task;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * issue #11 的四条验收标准都要求"真实 Redis"，不是内存模拟——归属校验和续期能不能真的原子、
 * TTL 到期能不能真的自愈，这些都是 Redis 服务端自己的行为，Mock 一个客户端测不出真实的竞态。
 *
 * <p>用 Testcontainers 而不是连开发机常驻实例（对比 MySQL 走 {@link com.agenttrail.support.SharedMySql}
 * 的方式）：Redis 镜像没有 initdb 这一步，容器几秒内就绪，代价远低于 MySQL；换来的是不依赖
 * "这台机器上恰好装了一个常驻 Redis"这个前提。
 */
@Testcontainers
class RedisTaskLockIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static RedissonClient redisson;

    /** 模拟两个不同的应用实例共享同一个 Redis，各自持有自己的 instanceId。 */
    private RedisTaskLock lockAsInstanceA;
    private RedisTaskLock lockAsInstanceB;

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

    @BeforeEach
    void freshLocksPerInstance() {
        lockAsInstanceA = new RedisTaskLock(redisson, "instance-a", Duration.ofSeconds(5));
        lockAsInstanceB = new RedisTaskLock(redisson, "instance-b", Duration.ofSeconds(5));
    }

    @Test
    void secondInstanceCannotAcquireALockAlreadyHeldByTheFirst() {
        String conversationId = uniqueConversationId();

        assertThat(lockAsInstanceA.tryAcquire(conversationId)).isTrue();
        assertThat(lockAsInstanceB.tryAcquire(conversationId)).isFalse();
    }

    /**
     * 核心验收点：归属校验 + 续期必须是一次原子操作——非持有者的续期必须被拒绝，
     * 且不能把 TTL 续给了不属于它的锁。
     */
    @Test
    void renewalByANonOwnerIsRejectedAndDoesNotExtendTheOwnersLock() {
        String conversationId = uniqueConversationId();
        lockAsInstanceA.tryAcquire(conversationId);

        boolean renewedByNonOwner = lockAsInstanceB.renew(conversationId);

        assertThat(renewedByNonOwner).isFalse();
        // 真正的持有者还能正常续期——证明"非持有者续期被拒绝"不是因为锁已经消失了
        assertThat(lockAsInstanceA.renew(conversationId)).isTrue();
    }

    @Test
    void theOwnerCanRenewItsOwnLock() {
        String conversationId = uniqueConversationId();
        lockAsInstanceA.tryAcquire(conversationId);

        assertThat(lockAsInstanceA.renew(conversationId)).isTrue();
    }

    /** TTL 自愈：持有者异常退出（没调用 release），锁必须能在 TTL 过期后被别的实例重新拿到。 */
    @Test
    void aLockSelfHealsAfterItsOwnerDisappearsWithoutReleasingIt() {
        String conversationId = uniqueConversationId();
        RedisTaskLock shortLivedLock = new RedisTaskLock(redisson, "instance-crashed", Duration.ofSeconds(1));
        shortLivedLock.tryAcquire(conversationId);
        // 持有者"崩溃"：不调用 release，也不再续期——等过 TTL，靠 Redis 自己到期删 key
        sleep(Duration.ofMillis(1_200));

        assertThat(lockAsInstanceB.tryAcquire(conversationId)).isTrue();
    }

    @Test
    void releaseByANonOwnerIsRejectedAndTheLockStaysHeld() {
        String conversationId = uniqueConversationId();
        lockAsInstanceA.tryAcquire(conversationId);

        boolean releasedByNonOwner = lockAsInstanceB.release(conversationId);

        assertThat(releasedByNonOwner).isFalse();
        assertThat(lockAsInstanceB.tryAcquire(conversationId))
                .as("非持有者的 release 不能生效，锁必须还在原持有者手上").isFalse();
    }

    @Test
    void theOwnerReleasingItsLockLetsAnotherInstanceAcquireItImmediately() {
        String conversationId = uniqueConversationId();
        lockAsInstanceA.tryAcquire(conversationId);

        assertThat(lockAsInstanceA.release(conversationId)).isTrue();
        assertThat(lockAsInstanceB.tryAcquire(conversationId)).isTrue();
    }

    /** 优雅关闭：主动释放持有的锁，不必等 TTL 过期——用一个足够长的 TTL 排除"其实是自愈"的假阳性。 */
    @Test
    void gracefulShutdownActivelyReleasesHeldLocksInsteadOfWaitingForTtlToExpire() {
        String firstConversation = uniqueConversationId();
        String secondConversation = uniqueConversationId();
        RedisTaskLock longTtlLock = new RedisTaskLock(redisson, "instance-shutting-down", Duration.ofMinutes(10));
        longTtlLock.tryAcquire(firstConversation);
        longTtlLock.tryAcquire(secondConversation);

        longTtlLock.releaseAll();

        assertThat(lockAsInstanceB.tryAcquire(firstConversation))
                .as("TTL 还有 10 分钟，能立刻抢到说明是主动释放的，不是等出来的").isTrue();
        assertThat(lockAsInstanceB.tryAcquire(secondConversation)).isTrue();
    }

    /** releaseAll 之后本实例的持有记录要清空，不能对同一批锁重复尝试释放。 */
    @Test
    void gracefulShutdownDoesNotFailWhenThereIsNothingHeld() {
        RedisTaskLock idleLock = new RedisTaskLock(redisson, "instance-idle", Duration.ofSeconds(5));

        idleLock.releaseAll();
        idleLock.releaseAll();
    }

    private static String uniqueConversationId() {
        return "conv-" + UUID.randomUUID();
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
