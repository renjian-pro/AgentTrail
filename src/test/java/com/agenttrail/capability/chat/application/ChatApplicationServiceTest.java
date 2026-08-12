package com.agenttrail.capability.chat.application;

import com.agenttrail.conversation.application.ConversationPort;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentEvent;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRuntimePort;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatApplicationServiceTest {

    @Test
    void sendResolvesTheToolCallingFallbackAndMapsRuntimeEventsToTheStableEnvelope() {
        AgentRuntimePort defaultRuntime = mock(AgentRuntimePort.class);
        AgentRuntimePort toolRuntime = mock(AgentRuntimePort.class);
        RunId runId = RunId.of("run-1");
        when(toolRuntime.start(any())).thenReturn(new AgentRunHandle(runId, Flux.just(
                new AgentEvent.Started(runId, ConversationId.of("conversation-1")),
                new AgentEvent.TextDelta(runId, "answer"),
                new AgentEvent.Completed(runId, ConversationId.of("conversation-1"), 7L))));

        ChatApplicationService service = new ChatApplicationService(
                new RuntimeProfileRegistry(Map.of("qwen-plus", defaultRuntime, "deepseek-chat", toolRuntime), "qwen-plus"),
                mock(ConversationPort.class));

        List<EventEnvelope> events = service.send(new ExecutionPrincipal("user-1", "tenant-1"),
                        "conversation-1", "qwen-plus", "run a tool", new ToolScope(true, true, Set.of()))
                .collectList().block();

        assertThat(events).extracting(EventEnvelope::type)
                .containsExactly("RunStarted", "ModelDelta", "RunCompleted");
        assertThat(events).extracting(EventEnvelope::sequence).containsExactly(1L, 2L, 3L);
        verify(toolRuntime).start(any());
        verify(defaultRuntime, never()).start(any());
    }

    @Test
    void stopHonorsConversationOwnershipBeforeCallingTheRuntime() {
        AgentRuntimePort runtime = mock(AgentRuntimePort.class);
        ConversationPort conversations = mock(ConversationPort.class);
        when(conversations.belongsTo("conversation-1", new ExecutionPrincipal("user-1", "tenant-1")))
                .thenReturn(true);
        ChatApplicationService service = new ChatApplicationService(
                new RuntimeProfileRegistry(Map.of("qwen-plus", runtime), "qwen-plus"), conversations);

        assertThat(service.stop(new ExecutionPrincipal("user-1", "tenant-1"), "conversation-1")).isTrue();
        assertThat(service.stop(new ExecutionPrincipal("user-1", "tenant-1"), "conversation-2")).isFalse();
        verify(runtime).cancel(RunId.of("conversation-1"),
                com.agenttrail.runtime.api.CancellationReason.USER_REQUESTED);
        verify(runtime, never()).cancel(RunId.of("conversation-2"),
                com.agenttrail.runtime.api.CancellationReason.USER_REQUESTED);
    }
}
