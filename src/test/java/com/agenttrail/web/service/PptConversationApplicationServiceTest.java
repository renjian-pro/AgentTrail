package com.agenttrail.web.service;

import com.agenttrail.capability.ppt.application.PptMessageRouter;
import com.agenttrail.capability.ppt.application.PptPreflightOutcome;
import com.agenttrail.capability.ppt.application.PptRequirementPreflight;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.web.dto.PptMessageRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PptConversationApplicationServiceTest {

    private final PptApplicationService tasks = mock(PptApplicationService.class);
    private final PptRequirementPreflight preflight = mock(PptRequirementPreflight.class);
    private final ConversationDigestService digests = mock(ConversationDigestService.class);
    private final CapabilityConversationService conversations = mock(CapabilityConversationService.class);
    private final PptConversationApplicationService service = new PptConversationApplicationService(
            tasks, preflight, digests, conversations);

    @Test
    void keepsRequirementCollectionConversationalAndDoesNotCreateATask() {
        PptMessageRequest request = new PptMessageRequest("c-1", "生成 PPT", "key-1");
        when(tasks.routeMessage("u-1", request)).thenReturn(
                new PptMessageRouter.Decision(PptMessageRouter.Action.CREATE, null, "create-fallback"));
        when(digests.withContext("c-1", "生成 PPT", "ppt-preflight")).thenReturn("生成 PPT");
        when(preflight.assess("c-1", "生成 PPT")).thenReturn(
                new PptPreflightOutcome(false, "还需要主题、页数、风格和受众。", null));

        var outcome = service.handle("u-1", request);

        assertThat(outcome.hasTask()).isFalse();
        assertThat(outcome.assistantMessage()).contains("主题", "页数", "风格", "受众");
        verify(tasks, never()).create(any(), any(), any(), any());
        verify(tasks, never()).handleMessage(any(), any());
        verify(conversations).recordSuccess(eq("u-1"), eq("c-1"), eq("生成 PPT"),
                eq("还需要主题、页数、风格和受众。"), eq("ppt-preflight"), isNull(), anyLong());
    }

    @Test
    void createsTheTaskOnlyWhenPreflightSaysTheRequirementIsReady() {
        PptMessageRequest request = new PptMessageRequest("c-1", "管理层，10页，商务简约", "key-1");
        when(tasks.routeMessage("u-1", request)).thenReturn(
                new PptMessageRouter.Decision(PptMessageRouter.Action.CREATE, null, "create-fallback"));
        when(digests.withContext("c-1", request.message(), "ppt-preflight")).thenReturn("完整上下文");
        when(preflight.assess("c-1", "完整上下文")).thenReturn(
                new PptPreflightOutcome(true, "需求已确认，开始生成 PPT。", "完整结构化需求"));
        when(tasks.create("u-1", "c-1", "完整结构化需求", "key-1"))
                .thenReturn(new PptApplicationService.Result(17L, PptMessageRouter.Action.CREATE, true));

        var outcome = service.handle("u-1", request);

        assertThat(outcome.hasTask()).isTrue();
        assertThat(outcome.taskId()).isEqualTo(17L);
        verify(tasks).create("u-1", "c-1", "完整结构化需求", "key-1");
    }

    @Test
    void delegatesMessagesForAnExistingTaskWithoutRunningPreflightAgain() {
        PptMessageRequest request = new PptMessageRequest("c-1", "把第二页改成流程图", "key-1");
        when(tasks.routeMessage("u-1", request)).thenReturn(
                new PptMessageRouter.Decision(PptMessageRouter.Action.MODIFY, 9L, "modify"));
        when(tasks.handleMessage("u-1", request)).thenReturn(
                new PptApplicationService.Result(10L, PptMessageRouter.Action.MODIFY, true));

        var outcome = service.handle("u-1", request);

        assertThat(outcome.taskId()).isEqualTo(10L);
        verify(preflight, never()).assess(any(), any());
    }
}
