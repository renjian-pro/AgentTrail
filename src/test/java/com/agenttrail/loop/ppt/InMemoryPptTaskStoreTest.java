package com.agenttrail.loop.ppt;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(new InMemoryPptTaskStore().findById(999L)).isEmpty();
    }
}
