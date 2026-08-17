package com.agenttrail.infrastructure.runtime;

import com.agenttrail.loop.hook.ToolRiskRegistry;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.InMemoryPauseStateStore;
import com.agenttrail.loop.pause.PauseReason;
import com.agenttrail.loop.pause.PauseState;
import com.agenttrail.loop.pause.PendingToolCall;
import com.agenttrail.loop.pause.SafePoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PauseStatePausedRunAdapterTest {

    @Test
    void projectsOwnershipVariantAndSanitizedPendingToolsFromTheSnapshot() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        store.save(new PauseState("conv-1", List.of(),
                List.of(new PendingToolCall("call-1", "write_file",
                        "{\"path\":\"a.txt\",\"token\":\"secret\"}")),
                PauseReason.HITL_APPROVAL, SafePoint.BEFORE_TOOL_EXECUTION, "write",
                new RunnableParams("conv-1", "user-1", Map.of(
                        "webSearchEnabled", "true", "analyticsEnabled", false)),
                "deepseek-chat", 2, 99L));

        var paused = new PauseStatePausedRunAdapter(store, ToolRiskRegistry.defaults())
                .find("conv-1").orElseThrow();

        assertThat(paused.userId()).isEqualTo("user-1");
        assertThat(paused.modelId()).isEqualTo("deepseek-chat");
        assertThat(paused.webSearchEnabled()).isTrue();
        assertThat(paused.analyticsEnabled()).isFalse();
        assertThat(paused.pendingTools()).singleElement().satisfies(tool -> {
            assertThat(tool.toolName()).isEqualTo("write_file");
            assertThat(tool.riskLevel()).isEqualTo("HIGH_RISK");
            assertThat(tool.arguments()).contains("\"path\":\"a.txt\"")
                    .contains("\"token\":\"***\"")
                    .doesNotContain("secret");
        });
    }
}
