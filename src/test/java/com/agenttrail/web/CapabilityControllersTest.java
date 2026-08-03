package com.agenttrail.web;

import com.agenttrail.loop.deepresearch.DeepResearchReport;
import com.agenttrail.loop.deepresearch.DeepResearchService;
import com.agenttrail.loop.ppt.PptGenerationService;
import com.agenttrail.loop.ppt.PptState;
import com.agenttrail.loop.ppt.PptTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityControllersTest {

    @Test
    void deepResearchRecordsItsStructuredResultInTheRequestedConversation() {
        DeepResearchService researchService = mock(DeepResearchService.class);
        CapabilityConversationService conversationService = mock(CapabilityConversationService.class);
        DeepResearchReport report = DeepResearchReport.completed("Java 就业趋势", List.of(), "研究结论");
        when(researchService.research("研究 Java")).thenReturn(report);
        DeepResearchController controller = new DeepResearchController(researchService, conversationService);

        assertThat(controller.research(new DeepResearchRequest("conversation-1", "研究 Java"))).isEqualTo(report);

        verify(conversationService).recordSuccess(eq("conversation-1"), eq("研究 Java"), eq("研究结论"),
                eq("research"), eq(report), anyLong());
    }

    @Test
    void pptRecordsItsTaskInTheRequestedConversation() throws IOException {
        PptGenerationService pptService = mock(PptGenerationService.class);
        CapabilityConversationService conversationService = mock(CapabilityConversationService.class);
        Path generatedPpt = Files.createTempFile("agenttrail-ppt-", ".pptx");
        when(pptService.create("conversation-2", "生成季度汇报")).thenReturn(9L);
        when(pptService.describe(9L)).thenReturn(Optional.of(
                new PptTask(9L, "conversation-2", PptState.SUCCESS, null, "{}", 1L, 2L)));
        when(pptService.outputPathOf(9L)).thenReturn(generatedPpt.toString());
        PptGenerationController controller = new PptGenerationController(pptService, conversationService);

        try {
            PptGenerationResponse response = controller.create(
                    new PptGenerationRequest("conversation-2", "生成季度汇报"));

            assertThat(response.taskId()).isEqualTo(9L);
            assertThat(response.outputPath()).isEqualTo("/agent/v1/ppt/9/download");
            verify(conversationService).recordSuccess(eq("conversation-2"), eq("生成季度汇报"),
                    eq("PPT 任务状态：SUCCESS"), eq("ppt"), eq(response), anyLong());
        } finally {
            Files.deleteIfExists(generatedPpt);
        }
    }

    @Test
    void servesGeneratedPptAsDownloadInsteadOfExposingItsServerPath() throws IOException {
        PptGenerationService pptService = mock(PptGenerationService.class);
        CapabilityConversationService conversationService = mock(CapabilityConversationService.class);
        Path generatedPpt = Files.createTempFile("agenttrail-download-", ".pptx");
        Files.writeString(generatedPpt, "pptx-test-content");
        when(pptService.outputPathOf(9L)).thenReturn(generatedPpt.toString());
        PptGenerationController controller = new PptGenerationController(pptService, conversationService);

        try {
            ResponseEntity<Resource> response = controller.download(9L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("attachment")
                    .contains(generatedPpt.getFileName().toString());
            assertThat(response.getBody()).isNotNull();
        } finally {
            Files.deleteIfExists(generatedPpt);
        }
    }
}
