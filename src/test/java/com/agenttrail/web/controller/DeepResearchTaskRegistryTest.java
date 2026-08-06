package com.agenttrail.web.controller;
import com.agenttrail.web.dto.DeepResearchTaskResponse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

class DeepResearchTaskRegistryTest {

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
}
