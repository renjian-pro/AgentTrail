package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.IngestedFile;
import com.agenttrail.capability.chat.application.ChatApplicationService;
import com.agenttrail.capability.chat.application.RuntimeProfileRegistry;
import com.agenttrail.capability.chat.application.PausedRunPort;
import com.agenttrail.platform.tools.PendingToolView;
import com.agenttrail.platform.tools.ToolRiskLevel;
import com.agenttrail.conversation.application.ConversationPort;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentEvent;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRuntimeException;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.runtime.api.ResumeCommand;
import com.agenttrail.platform.error.ErrorCode;
import com.agenttrail.web.dto.AgentChatRequest;
import com.agenttrail.web.dto.AgentApprovalRequest;
import com.agenttrail.web.dto.DeepResearchTaskResponse;
import com.agenttrail.web.dto.FileUploadResponse;
import com.agenttrail.web.dto.PptGenerationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;

class HttpContractSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deepResearchTaskResponseShapeIncludesProgress() {
        DeepResearchTaskResponse response = DeepResearchTaskResponse.running(9L);
        assertThat(fieldNames(response)).containsExactlyInAnyOrder("taskId", "status", "report", "errorMsg",
                "currentStep");
        assertThat(response.currentStep()).isEqualTo("CLARIFYING");
    }

    /**
     * clarifyingQuestion 是需求澄清引入的第五个字段：状态停在 AWAITING_INPUT 时承载助手的追问。
     * 它和 errorMsg 是并列的两个"为什么没继续跑"的原因位，前端据此决定渲染回答框还是报错——
     * 合并成一个字段会让"等你补充信息"和"跑挂了"在协议层就分不开。
     */
    @Test
    void pptResponseCarriesClarifyingQuestionAlongsideTheOriginalFour() {
        assertThat(fieldNames(new PptGenerationResponse(9L, null, null, null)))
                .containsExactlyInAnyOrder("taskId", "status", "errorMsg", "outputPath", "clarifyingQuestion");
    }

    @Test
    void fileUploadResponseIncludesAsyncIngestTaskId() {
        IngestedFile file = new IngestedFile(1L, "notes.txt", FileKind.TEXT, 10L, 5, false);
        assertThat(fieldNames(FileUploadResponse.from(file))).containsExactlyInAnyOrder("fileId", "fileName",
                "kind", "sizeBytes", "parsedTextLength", "routedToRag", "ingestTaskId");
    }

    @Test
    void agentLoopSsePreservesEventNamesAndOrder() {
        AgentRuntimePort runtime = mock(AgentRuntimePort.class);
        when(runtime.start(any())).thenReturn(new AgentRunHandle(RunId.of("conv-1"), Flux.just(
                new AgentEvent.Started(RunId.of("conv-1"), ConversationId.of("conv-1")),
                new AgentEvent.TextDelta(RunId.of("conv-1"), "answer"),
                new AgentEvent.Completed(RunId.of("conv-1"), ConversationId.of("conv-1"), null))));
        ConversationPort conversations = mock(ConversationPort.class);
        when(conversations.belongsTo(anyString(), any())).thenReturn(true);
        AgentLoopController controller = new AgentLoopController(new ChatApplicationService(
                new RuntimeProfileRegistry(java.util.Map.of("qwen-plus", runtime), "qwen-plus"), conversations));

        try (var ignored = mockStatic(StpUtil.class)) {
            ignored.when(StpUtil::getLoginIdAsString).thenReturn("user-1");
            List<ServerSentEvent<EventEnvelope>> events = controller.chat(
                    new AgentChatRequest("hello", null, null, false, null)).collectList().block();
            assertThat(events).extracting(ServerSentEvent::event)
                    .containsExactly("RunStarted", "ModelDelta", "RunCompleted");
        }
    }

    @Test
    void pausedSseExposesOnlyThePublicPendingToolContract() throws Exception {
        AgentRuntimePort runtime = mock(AgentRuntimePort.class);
        when(runtime.start(any())).thenReturn(new AgentRunHandle(RunId.of("conv-1"), Flux.just(
                new AgentEvent.Started(RunId.of("conv-1"), ConversationId.of("conv-1")),
                new AgentEvent.Paused(RunId.of("conv-1"), ConversationId.of("conv-1"), "HITL_APPROVAL",
                        List.of(new PendingToolView("call-1", "chargeCard",
                                "{\"amount\":100,\"api_token\":\"***\"}", ToolRiskLevel.HIGH_RISK))))));
        ConversationPort conversations = mock(ConversationPort.class);
        AgentLoopController controller = new AgentLoopController(new ChatApplicationService(
                new RuntimeProfileRegistry(Map.of("qwen-plus", runtime), "qwen-plus"), conversations));

        try (var ignored = mockStatic(StpUtil.class)) {
            ignored.when(StpUtil::getLoginIdAsString).thenReturn("user-1");
            List<ServerSentEvent<EventEnvelope>> events = controller.chat(
                    new AgentChatRequest("charge", "conv-1", "qwen-plus", false, null))
                    .collectList().block();

            assertThat(events).extracting(ServerSentEvent::event).containsExactly("RunStarted", "Paused");
            JsonNode payload = objectMapper.readTree(events.get(1).data().payload());
            assertThat(payload.path("pendingTools").get(0).path("riskLevel").asText()).isEqualTo("HIGH_RISK");
            assertThat(payload.path("pendingTools").get(0).path("arguments").asText())
                    .contains("***").doesNotContain("server-secret");
        }
    }

    @Test
    void pendingLookupAndRejectUseTheOwnedSnapshotAndOriginalRuntime() {
        AgentRuntimePort requestedRuntime = mock(AgentRuntimePort.class);
        AgentRuntimePort originalRuntime = mock(AgentRuntimePort.class);
        when(originalRuntime.resume(any(), any())).thenReturn(new AgentRunHandle(RunId.of("conv-1"), Flux.just(
                new AgentEvent.Completed(RunId.of("conv-1"), ConversationId.of("conv-1"), 9L))));
        PausedRunPort pausedRuns = conversationId -> "conv-1".equals(conversationId)
                ? Optional.of(new PausedRunPort.PausedRun("conv-1", "user-1", "deepseek-chat",
                "HITL_APPROVAL", 7L, true, false,
                List.of(new PendingToolView("call-1", "chargeCard", "{}", ToolRiskLevel.HIGH_RISK))))
                : Optional.empty();
        AgentLoopController controller = new AgentLoopController(new ChatApplicationService(
                new RuntimeProfileRegistry(Map.of(
                        "qwen-plus", requestedRuntime,
                        "deepseek-chat", originalRuntime), "qwen-plus"),
                mock(ConversationPort.class), pausedRuns));

        try (var ignored = mockStatic(StpUtil.class)) {
            ignored.when(StpUtil::getLoginIdAsString).thenReturn("user-1");
            assertThat(controller.pendingApproval("conv-1").pendingTools())
                    .extracting(tool -> tool.toolName()).containsExactly("chargeCard");

            List<ServerSentEvent<EventEnvelope>> events = controller.approve("conv-1",
                    new AgentApprovalRequest(false, "金额异常", "qwen-plus", false, null))
                    .collectList().block();
            assertThat(events).extracting(ServerSentEvent::event).containsExactly("RunCompleted");

            ArgumentCaptor<ResumeCommand> command = ArgumentCaptor.forClass(ResumeCommand.class);
            verify(originalRuntime).resume(any(), command.capture());
            assertThat(command.getValue()).isEqualTo(new ResumeCommand.Reject("金额异常"));
            verify(requestedRuntime, never()).resume(any(), any());

            ignored.when(StpUtil::getLoginIdAsString).thenReturn("other-user");
            assertThatThrownBy(() -> controller.pendingApproval("conv-1"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
            assertThatThrownBy(() -> controller.pendingApproval("missing"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
        }
    }

    @Test
    void concurrentResumeReturnsHttpConflictBeforeOpeningAnSseStream() {
        AgentRuntimePort runtime = mock(AgentRuntimePort.class);
        when(runtime.resume(any(), any())).thenThrow(
                new AgentRuntimeException(ErrorCode.CONCURRENT_EXECUTION, "正在恢复"));
        PausedRunPort pausedRuns = conversationId -> Optional.of(new PausedRunPort.PausedRun(
                "conv-1", "user-1", "qwen-plus", "HITL_APPROVAL", 1L,
                false, false, List.of()));
        AgentLoopController controller = new AgentLoopController(new ChatApplicationService(
                new RuntimeProfileRegistry(Map.of("qwen-plus", runtime), "qwen-plus"),
                mock(ConversationPort.class), pausedRuns));

        try (var ignored = mockStatic(StpUtil.class)) {
            ignored.when(StpUtil::getLoginIdAsString).thenReturn("user-1");
            assertThatThrownBy(() -> controller.approve("conv-1",
                    new AgentApprovalRequest(true, null, null, false, null)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
        }
    }

    private Set<String> fieldNames(Object value) {
        JsonNode node = objectMapper.valueToTree(value);
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
