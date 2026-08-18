package com.agenttrail.loop.tools.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIdempotencyStoreTest {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration TTL = Duration.ofHours(24);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);

    @Test
    void firstClaimWins() {
        assertThat(store.claim("k", LEASE).acquired()).isTrue();
    }

    @Test
    void secondClaimLosesAndSeesTheInFlightRecord() {
        store.claim("k", LEASE);

        IdempotencyClaim existing = store.claim("k", LEASE);

        assertThat(existing.acquired()).isFalse();
        assertThat(existing.existing().status()).isEqualTo(IdempotencyRecord.Status.IN_FLIGHT);
    }

    @Test
    void completedRecordIsReplayedToLaterClaims() {
        IdempotencyClaim owner = store.claim("k", LEASE);
        store.complete("k", owner.ownerToken(), "结果", TTL);

        IdempotencyClaim existing = store.claim("k", LEASE);

        assertThat(existing.acquired()).isFalse();
        assertThat(existing.existing().isCompleted()).isTrue();
        assertThat(existing.existing().result()).isEqualTo("结果");
    }

    @Test
    void releasedKeyCanBeClaimedAgain() {
        IdempotencyClaim owner = store.claim("k", LEASE);
        store.release("k", owner.ownerToken());

        assertThat(store.claim("k", LEASE).acquired()).isTrue();
    }

    /** release 只针对"还没完成"的占位，不能把已完成的结果也删掉——那等于把去重窗口清零。 */
    @Test
    void releaseDoesNotDropACompletedRecord() {
        IdempotencyClaim owner = store.claim("k", LEASE);
        store.complete("k", owner.ownerToken(), "结果", TTL);

        store.release("k", owner.ownerToken());

        assertThat(store.find("k")).isPresent();
    }

    /**
     * 踩坑点 #34 的直接体现：执行者恰好在 TOOL_EXECUTION 安全点被中断/进程被杀，
     * 记录会永远停在 IN_FLIGHT。租约到期后必须能重新抢占，否则这个键被永久毒化。
     */
    @Test
    void expiredLeaseAllowsAnotherClaim() {
        store.claim("k", LEASE);

        clock.advance(LEASE.plusSeconds(1));

        assertThat(store.claim("k", LEASE).acquired()).isTrue();
    }

    @Test
    void expiredCompletedRecordIsGoneAndTheKeyBecomesExecutableAgain() {
        IdempotencyClaim owner = store.claim("k", LEASE);
        store.complete("k", owner.ownerToken(), "结果", TTL);

        clock.advance(TTL.plusSeconds(1));

        assertThat(store.find("k")).isEmpty();
        assertThat(store.claim("k", LEASE).acquired()).isTrue();
    }

    @Test
    void findReturnsEmptyForAnUnknownKey() {
        assertThat(store.find("never-claimed")).isEmpty();
    }

    /**
     * claim 必须是原子的。"先 find 再 put"的写法下，多个线程会同时读到"没有记录"、
     * 然后都认为自己是首次执行者——副作用照样做 N 遍。并发抢 64 次，必须恰好一个赢家。
     */
    @Test
    void admitsExactlyOneWinnerWhenManyThreadsClaimAtOnce() throws Exception {
        int contenders = 64;
        try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
            List<Callable<Boolean>> attempts = IntStream.range(0, contenders)
                    .<Callable<Boolean>>mapToObj(i -> () -> store.claim("k", LEASE).acquired())
                    .toList();

            List<Future<Boolean>> results = pool.invokeAll(attempts);

            long winners = results.stream().filter(InMemoryIdempotencyStoreTest::valueOf).count();
            assertThat(winners).isEqualTo(1);
        }
    }

    private static boolean valueOf(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
