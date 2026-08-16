package com.agenttrail.loop.pause;

import com.agenttrail.runtime.api.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PauseStateJsonTest {

    record Plan(String title, int priority) {
    }

    @Test
    void roundTripsEveryMessageKindPendingCallsAndRuntimeParams() {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("I will call two tools")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "search", "{\"q\":\"weather\"}"),
                        new AssistantMessage.ToolCall("call-2", "function", "clock", "{}")))
                .build();
        ToolResponseMessage toolResponses = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "search", "sunny"),
                        new ToolResponseMessage.ToolResponse("call-2", "clock", "09:30")))
                .build();
        PauseState original = new PauseState(
                "conv-1",
                List.of(new SystemMessage("system rules"), new UserMessage("question"), assistant, toolResponses),
                List.of(new PendingToolCall("call-3", "approve", "{\"amount\":10}")),
                PauseReason.HITL_APPROVAL,
                SafePoint.BEFORE_TOOL_EXECUTION,
                "question",
                new RunnableParams("conv-1", "user-1", Map.of("tenantId", "tenant-7"), OutputType.of(Plan.class)),
                4,
                1_700_000_000_123L);

        PauseState restored = PauseStateJson.fromJson(PauseStateJson.toJson(original));

        assertThat(restored.conversationId()).isEqualTo("conv-1");
        assertThat(restored.reason()).isEqualTo(PauseReason.HITL_APPROVAL);
        assertThat(restored.safePoint()).isEqualTo(SafePoint.BEFORE_TOOL_EXECUTION);
        assertThat(restored.question()).isEqualTo("question");
        assertThat(restored.roundAtPause()).isEqualTo(4);
        assertThat(restored.pausedAtMillis()).isEqualTo(1_700_000_000_123L);
        assertThat(restored.pendingToolCalls()).containsExactly(
                new PendingToolCall("call-3", "approve", "{\"amount\":10}"));
        assertThat(restored.params().conversationId()).isEqualTo("conv-1");
        assertThat(restored.params().userId()).isEqualTo("user-1");
        assertThat(restored.params().toolParams()).containsEntry("tenantId", "tenant-7");
        assertThat(restored.params().outputType().type()).isEqualTo(Plan.class);

        assertThat(restored.messages()).hasSize(4);
        assertThat(restored.messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(restored.messages().get(0).getText()).isEqualTo("system rules");
        assertThat(restored.messages().get(1)).isInstanceOf(UserMessage.class);
        assertThat(restored.messages().get(1).getText()).isEqualTo("question");
        assertThat(((AssistantMessage) restored.messages().get(2)).getToolCalls())
                .extracting(AssistantMessage.ToolCall::id, AssistantMessage.ToolCall::name,
                        AssistantMessage.ToolCall::arguments)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("call-1", "search", "{\"q\":\"weather\"}"),
                        org.assertj.core.groups.Tuple.tuple("call-2", "clock", "{}"));
        assertThat(((ToolResponseMessage) restored.messages().get(3)).getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id, ToolResponseMessage.ToolResponse::name,
                        ToolResponseMessage.ToolResponse::responseData)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("call-1", "search", "sunny"),
                        org.assertj.core.groups.Tuple.tuple("call-2", "clock", "09:30"));
    }

    @Test
    void treatsMissingCollectionsAsEmptyAndMissingMessageContentAsEmptyText() {
        String json = """
                {
                  "conversationId":"conv-2",
                  "messages":[
                    {"role":"system"},
                    {"role":"assistant","content":null},
                    {"role":"unknown","content":"fallback user"}
                  ],
                  "reason":"USER_INTERRUPT",
                  "safePoint":"BEFORE_TOOL_EXECUTION",
                  "question":"q",
                  "params":{"conversationId":"conv-2","userId":"user-2"},
                  "roundAtPause":0,
                  "pausedAtMillis":1
                }
                """;

        PauseState restored = PauseStateJson.fromJson(json);

        assertThat(restored.pendingToolCalls()).isEmpty();
        assertThat(restored.params().toolParams()).isEmpty();
        assertThat(restored.params().outputType()).isNull();
        assertThat(restored.messages()).extracting(message -> message.getClass(), message -> message.getText())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SystemMessage.class, ""),
                        org.assertj.core.groups.Tuple.tuple(AssistantMessage.class, ""),
                        org.assertj.core.groups.Tuple.tuple(UserMessage.class, "fallback user"));
    }

    @Test
    void acceptsSnapshotsWithoutAnyMessageLists() {
        String json = """
                {
                  "conversationId":"conv-3",
                  "reason":"HITL_APPROVAL",
                  "safePoint":"BEFORE_TOOL_EXECUTION",
                  "question":"q",
                  "params":{"conversationId":"conv-3","userId":"user-3"},
                  "roundAtPause":1,
                  "pausedAtMillis":2
                }
                """;

        PauseState restored = PauseStateJson.fromJson(json);

        assertThat(restored.messages()).isEmpty();
        assertThat(restored.pendingToolCalls()).isEmpty();
    }

    @Test
    void rejectsMalformedSnapshotsWithAUsefulCause() {
        assertThatThrownBy(() -> PauseStateJson.fromJson("not json"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void rejectsAnOutputTypeThatIsNoLongerOnTheClasspath() {
        String json = """
                {
                  "conversationId":"conv-4",
                  "reason":"HITL_APPROVAL",
                  "safePoint":"BEFORE_TOOL_EXECUTION",
                  "question":"q",
                  "params":{
                    "conversationId":"conv-4",
                    "userId":"user-4",
                    "outputTypeClassName":"missing.package.RemovedType"
                  },
                  "roundAtPause":1,
                  "pausedAtMillis":2
                }
                """;

        assertThatThrownBy(() -> PauseStateJson.fromJson(json))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(ClassNotFoundException.class)
                .hasStackTraceContaining("missing.package.RemovedType");
    }
}
