package com.agenttrail.runtime.lifecycle;

import com.agenttrail.loop.task.RedisTaskLock;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the LeaseManager adapter preserves Redis ownership semantics across instances. */
@Testcontainers
class RedisLeaseManagerIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static RedissonClient redisson;
    private RedisLeaseManager leaseA;
    private RedisLeaseManager leaseB;

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
    void createIndependentApplicationLeases() {
        leaseA = new RedisLeaseManager(new RedisTaskLock(redisson, "lease-instance-a", Duration.ofSeconds(5)));
        leaseB = new RedisLeaseManager(new RedisTaskLock(redisson, "lease-instance-b", Duration.ofSeconds(5)));
    }

    @Test
    void onlyOneApplicationInstanceCanOwnTheSameResource() {
        String resource = "deep-research:" + System.nanoTime();

        assertThat(leaseA.tryAcquire(resource, Duration.ofSeconds(5))).isTrue();
        assertThat(leaseB.tryAcquire(resource, Duration.ofSeconds(5))).isFalse();
        assertThat(leaseB.renew(resource, Duration.ofSeconds(5))).isFalse();
        assertThat(leaseA.renew(resource, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void releasingTheOwnerAllowsTheOtherInstanceToAcquire() {
        String resource = "ppt-generation:" + System.nanoTime();

        assertThat(leaseA.tryAcquire(resource, Duration.ofSeconds(5))).isTrue();
        assertThat(leaseA.release(resource)).isTrue();
        assertThat(leaseB.tryAcquire(resource, Duration.ofSeconds(5))).isTrue();
    }
}
