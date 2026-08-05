package com.agenttrail.loop.hook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SessionBudgetTrackerTest {

    @Test
    void accumulatesPerConversationAndForgetsOnCompletion() {
        SessionBudgetTracker tracker = new SessionBudgetTracker(100);

        assertThat(tracker.record("conv-1", 40, 30)).isEqualTo(70);
        assertThat(tracker.overBudget("conv-1")).isFalse();
        assertThat(tracker.record("conv-1", 20, 11)).isEqualTo(101);
        assertThat(tracker.overBudget("conv-1")).isTrue();
        assertThat(tracker.overBudget("conv-2")).isFalse();

        tracker.forget("conv-1");

        assertThat(tracker.overBudget("conv-1")).isFalse();
        assertThat(tracker.record("conv-1", 1, 2)).isEqualTo(3);
    }

    @Test
    void concurrentRecordsDoNotLoseTokenCounts() throws Exception {
        SessionBudgetTracker tracker = new SessionBudgetTracker(Long.MAX_VALUE);
        int calls = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            futures.add(pool.submit(() -> tracker.record("conv-1", 2, 3)));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(futures.get(calls - 1).get()).isPositive();
        assertThat(tracker.record("conv-1", 0, 0)).isEqualTo(calls * 5L);
    }
}
