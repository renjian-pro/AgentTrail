package com.agenttrail.web.controller;
import com.agenttrail.web.dto.PptGenerationResponse;
import com.agenttrail.web.dto.PptGenerationRequest;
import com.agenttrail.web.dto.DeepResearchTaskResponse;
import com.agenttrail.web.dto.DeepResearchRequest;
import com.agenttrail.web.service.CapabilityConversationService;

import com.agenttrail.capability.deepresearch.DeepResearchReport;
import com.agenttrail.capability.deepresearch.DeepResearchService;
import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTask;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityControllersTest {

    /** 测试用的直接执行器——同线程内联跑 Runnable，不像生产那样跳到独立线程池，
     * 断言才能在 controller 方法返回后立即看到"后台任务"已经跑完的效果。 */
    private static final java.util.concurrent.Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void deepResearchRecordsItsStructuredResultInTheRequestedConversationOnceTheBackgroundRunCompletes() {
        DeepResearchService researchService = mock(DeepResearchService.class);
        CapabilityConversationService conversationService = mock(CapabilityConversationService.class);
        DeepResearchReport report = DeepResearchReport.completed("Java 就业趋势", List.of(), "研究结论");
        when(researchService.research(eq("研究 Java"), any())).thenReturn(report);
        DeepResearchController controller = new DeepResearchController(researchService, conversationService, DIRECT_EXECUTOR);

        DeepResearchTaskResponse created = controller.research(
                new DeepResearchRequest("conversation-1", "研究 Java", null, null));
        // DIRECT_EXECUTOR 是同线程执行，research() 返回时后台任务其实已经跑完了——
        // 轮询端点应该已经能读到 SUCCESS，不用真的等。
        DeepResearchTaskResponse polled = controller.status(created.taskId());

        assertThat(polled.status()).isEqualTo(DeepResearchTaskResponse.SUCCESS);
        assertThat(polled.report()).isEqualTo(report);
        verify(conversationService).recordSuccess(isNull(), eq("conversation-1"), eq("研究 Java"), eq("研究结论"),
                eq("research"), eq(report), anyLong());
    }

    @Test
    void pptRecordsItsTaskInTheRequestedConversationOnceTheBackgroundRunCompletes() throws IOException {
        PptGenerationService pptService = mock(PptGenerationService.class);
        CapabilityConversationService conversationService = mock(CapabilityConversationService.class);
        Path generatedPpt = Files.createTempFile("agenttrail-ppt-", ".pptx");
        when(pptService.prepare("legacy", "conversation-2", "生成季度汇报")).thenReturn(9L);
        when(pptService.describe(9L)).thenReturn(Optional.of(
                new PptTask(9L, "conversation-2", PptState.SUCCESS, null, "{}", 1L, 2L)));
        when(pptService.outputPathOf(9L)).thenReturn(generatedPpt.toString());
        PptGenerationController controller = new PptGenerationController(pptService, conversationService, DIRECT_EXECUTOR);

        try {
            PptGenerationResponse response = controller.create(
                    new PptGenerationRequest("conversation-2", "生成季度汇报"));

            assertThat(response.taskId()).isEqualTo(9L);
            assertThat(response.outputPath()).isEqualTo("/agent/v1/ppt/9/download");
            verify(pptService).run(9L);
            verify(conversationService).recordSuccess(isNull(), eq("conversation-2"), eq("生成季度汇报"),
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
        PptGenerationController controller = new PptGenerationController(pptService, conversationService, DIRECT_EXECUTOR);

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
