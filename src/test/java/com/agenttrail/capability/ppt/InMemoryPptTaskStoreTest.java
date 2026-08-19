package com.agenttrail.capability.ppt;

import org.junit.jupiter.api.Test;

import java.util.List;
import com.agenttrail.platform.error.RetryClass;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPptTaskStoreTest {

    @Test
    void createsATaskWithInitStatusAndNoError() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        PptGenerationContext context = PptGenerationContext.initial("conv-1", "问题");

        long id = store.create("conv-1", context);

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.INIT);
        assertThat(task.errorMsg()).isNull();
        assertThat(task.conversationId()).isEqualTo("conv-1");
        assertThat(task.runStatus()).isEqualTo(PptRunStatus.QUEUED);
        assertThat(task.revision()).isZero();
        assertThat(task.contextVersion()).isEqualTo(PptGenerationContext.CURRENT_CONTEXT_VERSION);
    }

    @Test
    void conditionalAdvanceIncrementsRevisionAndAppendsASuccessEvent() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "问题"));

        boolean advanced = store.conditionalAdvance(id, PptState.INIT, 0,
                PptState.CLARIFY, PptRunStatus.RUNNING,
                PptGenerationContext.initial("conv-1", "问题"));

        assertThat(advanced).isTrue();
        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.CLARIFY);
        assertThat(task.runStatus()).isEqualTo(PptRunStatus.RUNNING);
        assertThat(task.revision()).isEqualTo(1);
        assertThat(task.attempt()).as("推进到下一阶段时，该阶段从第 1 次执行开始计数").isEqualTo(1);
        assertThat(store.eventsForTask(id)).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo(PptState.INIT);
            assertThat(event.outcome()).isEqualTo(PptCheckpointEvent.OUTCOME_SUCCEEDED);
            assertThat(event.revisionBefore()).isZero();
            assertThat(event.revisionAfter()).isEqualTo(1);
        });
    }

    @Test
    void idempotentCreationReturnsTheSameTaskAcrossRetries() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        PptGenerationContext context = PptGenerationContext.initial("conv-1", "问题");

        PptTaskCreation first = store.createIdempotent("user-1", "conv-1", context, "CREATE", "key-1");
        PptTaskCreation replay = store.createIdempotent("user-1", "conv-1", context, "CREATE", "key-1");

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.taskId()).isEqualTo(first.taskId());
    }

    @Test
    void claimAndRetryMetadataArePersistedAndRecoveryOnlyReturnsDueTasks() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long id = store.create("user-1", "conv-1", PptGenerationContext.initial("conv-1", "问题"));

        assertThat(store.claim(id, PptState.INIT, 0)).isTrue();
        PptFailure failure = new PptFailure("PPT_TIMEOUT", PptState.INIT, true,
                RetryClass.RETRIABLE, 1, "请求超时，请稍后重试", System.currentTimeMillis());
        long retryAt = System.currentTimeMillis() + 60_000;
        assertThat(store.recordFailureIfCurrent(id, PptState.INIT, 1, failure,
                PptRunStatus.RETRY_WAIT, retryAt)).isTrue();
        assertThat(store.findById(id).orElseThrow().attempt()).isEqualTo(1);
        assertThat(store.recoverableTaskIds(System.currentTimeMillis(), 10)).isEmpty();
        assertThat(store.recoverableTaskIds(retryAt, 10)).containsExactly(id);
    }

    @Test
    void recoveryIncludesCancelRequestedTasksSoAWorkerCanFinishCancellationAfterCrash() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long id = store.create("user-1", "conv-1", PptGenerationContext.initial("conv-1", "问题"));

        store.requestCancel(id);

        assertThat(store.recoverableTaskIds(System.currentTimeMillis(), 10)).containsExactly(id);
    }

    @Test
    void staleConditionalAdvanceDoesNotOverwriteTheNewerCheckpoint() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "问题"));
        PptGenerationContext context = PptGenerationContext.initial("conv-1", "问题");

        assertThat(store.conditionalAdvance(id, PptState.INIT, 0,
                PptState.CLARIFY, PptRunStatus.RUNNING, context)).isTrue();
        assertThat(store.conditionalAdvance(id, PptState.INIT, 0,
                PptState.REQUIREMENT, PptRunStatus.RUNNING, context)).isFalse();

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.CLARIFY);
        assertThat(task.revision()).isEqualTo(1);
        assertThat(store.eventsForTask(id)).hasSize(1);
    }

    @Test
    void advanceUpdatesStatusAndContextAndClearsAnyPriorError() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "问题"));
        store.markFailed(id, PptState.REQUIREMENT, "之前失败过");

        PptGenerationContext advanced = PptGenerationContext.initial("conv-1", "问题")
                .withSearchMaterials(List.of("素材"));
        store.advance(id, PptState.SEARCH, advanced);

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.SEARCH);
        assertThat(task.errorMsg()).as("成功推进要清空上一次失败的痕迹").isNull();
        assertThat(PptContextJson.fromJson(task.contextJson()).searchMaterials()).containsExactly("素材");
    }

    @Test
    void markFailedKeepsStatusOnTheFailingStateAndRecordsTheError() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "问题"));

        store.markFailed(id, PptState.OUTLINE, "模型输出解析失败");

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.OUTLINE);
        assertThat(task.errorMsg()).isEqualTo("模型输出解析失败");
        assertThat(task.runStatus()).isEqualTo(PptRunStatus.FAILED);
        assertThat(store.eventsForTask(id)).singleElement()
                .extracting(PptCheckpointEvent::outcome)
                .isEqualTo(PptCheckpointEvent.OUTCOME_FAILED);
    }

    @Test
    void appendsDetailProgressWithoutAdvancingTheTaskRevision() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "问题"));
        long revision = store.findById(id).orElseThrow().revision();

        store.appendProgressEvent(id, PptState.IMAGE, "图片生成完成（3/6）", null);

        assertThat(store.findById(id).orElseThrow().revision()).isEqualTo(revision);
        assertThat(store.eventsForTask(id)).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(PptCheckpointEvent.OUTCOME_PROGRESS);
            assertThat(event.outputSummary()).isEqualTo("图片生成完成（3/6）");
            assertThat(event.stage()).isEqualTo(PptState.IMAGE);
        });
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(new InMemoryPptTaskStore().findById(999L)).isEmpty();
    }

    @Test
    void findLatestByConversationIdReturnsTheMostRecentlyCreatedTaskForThatConversation() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long first = store.create("conv-1", PptGenerationContext.initial("conv-1", "第一次请求"));
        long second = store.create("conv-1", PptGenerationContext.initial("conv-1", "第二次请求"));
        store.create("conv-2", PptGenerationContext.initial("conv-2", "另一个会话的请求"));

        PptTask latest = store.findLatestByConversationId("conv-1").orElseThrow();

        assertThat(latest.id()).as("id 更大的那条（后创建的）才是最新").isEqualTo(second);
        assertThat(latest.id()).isNotEqualTo(first);
    }

    @Test
    void findLatestByConversationIdReturnsEmptyWhenThatConversationHasNoTask() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        store.create("conv-1", PptGenerationContext.initial("conv-1", "问题"));

        assertThat(store.findLatestByConversationId("conv-从来没有过任务")).isEmpty();
    }

    @Test
    void conversationHistoryAndLatestLookupAreScopedToTheOwningUser() {
        InMemoryPptTaskStore store = new InMemoryPptTaskStore();
        long userATask = store.create("user-a", "shared-conversation", PptGenerationContext.initial("shared-conversation", "A"));
        long userBTask = store.create("user-b", "shared-conversation", PptGenerationContext.initial("shared-conversation", "B"));

        assertThat(store.findLatestByConversationId("user-a", "shared-conversation"))
                .get().extracting(PptTask::id).isEqualTo(userATask);
        assertThat(store.findAllByConversationId("user-a", "shared-conversation"))
                .extracting(PptTask::id).containsExactly(userATask);
        assertThat(store.findAllByConversationId("user-b", "shared-conversation"))
                .extracting(PptTask::id).containsExactly(userBTask);
    }
}
