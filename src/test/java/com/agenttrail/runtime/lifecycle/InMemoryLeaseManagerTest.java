package com.agenttrail.runtime.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLeaseManagerTest {

    @Test
    void twoWorkerInstancesCompeteForTheSameResource() {
        InMemoryLeaseManager first = new InMemoryLeaseManager();
        InMemoryLeaseManager second = new InMemoryLeaseManager();

        assertThat(first.tryAcquire("ppt-generation-test", Duration.ofSeconds(5))).isTrue();
        assertThat(second.tryAcquire("ppt-generation-test", Duration.ofSeconds(5))).isFalse();
        assertThat(second.renew("ppt-generation-test", Duration.ofSeconds(5))).isFalse();
        assertThat(first.release("ppt-generation-test")).isTrue();
        assertThat(second.tryAcquire("ppt-generation-test", Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void anExpiredLeaseCanBeClaimedByAnotherWorker() throws InterruptedException {
        InMemoryLeaseManager first = new InMemoryLeaseManager();
        InMemoryLeaseManager second = new InMemoryLeaseManager();

        assertThat(first.tryAcquire("expired-lease-test", Duration.ofMillis(20))).isTrue();
        Thread.sleep(40);

        assertThat(second.tryAcquire("expired-lease-test", Duration.ofSeconds(1))).isTrue();
    }
}
