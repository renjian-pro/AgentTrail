package com.agenttrail.web.service;

import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.capability.ppt.PptRunStatus;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTask;
import com.agenttrail.capability.ppt.application.PptMessageRouter;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.web.dto.PptMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PptApplicationServiceTest {

    private final PptGenerationService generation = mock(PptGenerationService.class);
    private final CapabilityConversationService conversations = mock(CapabilityConversationService.class);
    private final ConversationDigestService digest = mock(ConversationDigestService.class);
    private final Executor queuedExecutor = command -> { };
    private final PptApplicationService service = new PptApplicationService(
            generation, conversations, queuedExecutor, digest, new PptMessageRouter());

    @Test
    void routesAnOrdinaryReplyIntoTheWaitingTask() {
        when(generation.describeConversation("u-1", "c-1"))
                .thenReturn(List.of(task(7, PptState.AWAITING_INPUT, PptRunStatus.WAITING_INPUT)));

        var result = service.handleMessage("u-1", new PptMessageRequest("c-1", "AI Agent", null));

        assertThat(result.action()).isEqualTo(PptMessageRouter.Action.ANSWER);
        assertThat(result.taskId()).isEqualTo(7);
        verify(generation).answerClarification("u-1", 7, "AI Agent");
    }

    @Test
    void cancelsAndReplacesAnActiveTaskWhenTheMessageIsAModification() {
        when(generation.describeConversation("u-1", "c-1"))
                .thenReturn(List.of(task(7, PptState.IMAGE, PptRunStatus.RUNNING)));
        when(generation.describe("u-1", 7)).thenReturn(java.util.Optional.of(
                task(7, PptState.IMAGE, PptRunStatus.RUNNING)));
        when(generation.prepareReplacement("u-1", 7, "第二页改成流程图", "key-1"))
                .thenReturn(8L);

        var result = service.handleMessage("u-1",
                new PptMessageRequest("c-1", "第二页改成流程图", "key-1"));

        assertThat(result.action()).isEqualTo(PptMessageRouter.Action.MODIFY);
        assertThat(result.taskId()).isEqualTo(8);
        verify(generation).requestCancel(7);
        verify(generation).prepareReplacement("u-1", 7, "第二页改成流程图", "key-1");
    }

    @Test
    void explicitCancellationDoesNotCreateAnotherTask() {
        PptTask active = task(7, PptState.SCHEMA, PptRunStatus.RUNNING);
        when(generation.describeConversation("u-1", "c-1")).thenReturn(List.of(active));
        when(generation.describe("u-1", 7)).thenReturn(java.util.Optional.of(active));

        var result = service.handleMessage("u-1", new PptMessageRequest("c-1", "取消这次生成", null));

        assertThat(result.action()).isEqualTo(PptMessageRouter.Action.CANCEL);
        assertThat(result.scheduled()).isFalse();
        verify(generation).requestCancel(7);
    }

    private static PptTask task(long id, PptState state, PptRunStatus runStatus) {
        return new PptTask(id, "u-1", "c-1", state, runStatus, null,
                "{}", 1, 1, null, null,
                1, 0, 1, 2);
    }
}
