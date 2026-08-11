package com.agenttrail.web.controller;
import com.agenttrail.web.dto.DeepResearchTaskResponse;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

class DeepResearchTaskRegistryTest {

    @Test
    void keepsTerminalTaskReadableDuringRetentionAndEvictsItOnNextStartAfterExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        DeepResearchTaskRegistry registry = new DeepResearchTaskRegistry(clock, Duration.ofMinutes(10));
        long taskId = registry.start("user-1");
        registry.complete(taskId, null);

        assertThat(registry.find(taskId).orElseThrow().status()).isEqualTo(DeepResearchTaskResponse.SUCCESS);
        clock.advance(Duration.ofMinutes(10));
        registry.start("user-2");

        assertThat(registry.find(taskId)).isEmpty();
        assertThat(registry.belongsTo(taskId, "user-1")).isFalse();
    }

    @Test
    void cancellationKeepsOwnershipAndPreventsLateCompletionFromOverwritingCancelledState() {
        DeepResearchTaskRegistry registry = new DeepResearchTaskRegistry();
        long taskId = registry.start("user-1");
        FutureTask<Void> future = new FutureTask<>(() -> null);
        registry.attachFuture(taskId, future);

        assertThat(registry.runningTaskIdsFor("user-1")).containsExactly(taskId);
        assertThat(registry.cancel(taskId)).isTrue();
        assertThat(future.isCancelled()).isTrue();
        assertThat(registry.find(taskId).orElseThrow().status())
                .isEqualTo(DeepResearchTaskResponse.CANCELLED);
        assertThat(registry.runningTaskIdsFor("user-1")).isEmpty();
        assertThat(registry.belongsTo(taskId, "user-1")).isTrue();

        registry.complete(taskId, null);
        assertThat(registry.find(taskId).orElseThrow().status())
                .isEqualTo(DeepResearchTaskResponse.CANCELLED);
        assertThat(registry.cancel(taskId)).isFalse();
    }

    @Test
    void updatesOnlyRunningTasksAndPreservesTheLastStepAfterCompletion() {
        DeepResearchTaskRegistry registry = new DeepResearchTaskRegistry();
        long taskId = registry.start("user-1");

        registry.updateStep(taskId, "SEARCHING");
        assertThat(registry.find(taskId).orElseThrow().currentStep()).isEqualTo("SEARCHING");

        registry.complete(taskId, null);
        registry.updateStep(taskId, "SUMMARIZING");
        assertThat(registry.find(taskId).orElseThrow().currentStep()).isEqualTo("SEARCHING");
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration amount) {
            current = current.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
