package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.IngestedFile;
import com.agenttrail.capability.chat.application.ChatApplicationService;
import com.agenttrail.capability.chat.application.RuntimeProfileRegistry;
import com.agenttrail.conversation.application.ConversationPort;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentEvent;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.web.dto.AgentChatRequest;
import com.agenttrail.web.dto.AgentChatResponse;
import com.agenttrail.web.dto.DeepResearchTaskResponse;
import com.agenttrail.web.dto.FileUploadResponse;
import com.agenttrail.web.dto.PptGenerationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class HttpContractSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyChatResponseShapeIsFrozen() {
        assertThat(fieldNames(new AgentChatResponse("answer"))).containsExactly("answer");
    }

    @Test
    void deepResearchTaskResponseShapeIncludesProgress() {
        DeepResearchTaskResponse response = DeepResearchTaskResponse.running(9L);
        assertThat(fieldNames(response)).containsExactlyInAnyOrder("taskId", "status", "report", "errorMsg",
                "currentStep");
        assertThat(response.currentStep()).isEqualTo("CLARIFYING");
    }

    @Test
    void pptResponseKeepsItsFourFields() {
        assertThat(fieldNames(new PptGenerationResponse(9L, null, null, null)))
                .containsExactlyInAnyOrder("taskId", "status", "errorMsg", "outputPath");
    }

    @Test
    void fileUploadResponseKeepsItsSixFields() {
        IngestedFile file = new IngestedFile(1L, "notes.txt", FileKind.TEXT, 10L, 5, false);
        assertThat(fieldNames(FileUploadResponse.from(file))).containsExactlyInAnyOrder("fileId", "fileName",
                "kind", "sizeBytes", "parsedTextLength", "routedToRag");
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

    private Set<String> fieldNames(Object value) {
        JsonNode node = objectMapper.valueToTree(value);
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
