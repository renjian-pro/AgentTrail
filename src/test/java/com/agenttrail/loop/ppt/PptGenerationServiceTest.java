package com.agenttrail.loop.ppt;

import com.agenttrail.loop.ppt.support.RecordingPptGenerationStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只验证 {@link PptGenerationService} 自己的编排语义（分发表覆盖度、状态推进顺序、
 * checkpoint 写入时序、断点续传按状态粒度重跑）——不涉及任何真实 LLM 调用或 Python 子进程，
 * 全部状态用 {@link RecordingPptGenerationStrategy} 测试替身。真实各状态的具体行为（LLM
 * prompt/JSON 解析/Python 渲染）由各自的单测覆盖，端到端真实产出 pptx 见
 * {@code PptGenerationServiceIT}。
 */
class PptGenerationServiceTest {

    private static List<PptGenerationStrategy> allStates(List<String> log) {
        List<PptGenerationStrategy> strategies = new ArrayList<>();
        for (PptState state : List.of(PptState.INIT, PptState.REQUIREMENT, PptState.SEARCH, PptState.TEMPLATE,
                PptState.OUTLINE, PptState.SCHEMA, PptState.IMAGE, PptState.RENDER)) {
            strategies.add(new RecordingPptGenerationStrategy(state, log));
        }
        return strategies;
    }

    @Test
    void constructorRejectsWhenAStateHasNoRegisteredStrategy() {
        List<PptGenerationStrategy> incomplete = new ArrayList<>(allStates(new ArrayList<>()));
        incomplete.removeIf(s -> s.handledState() == PptState.SCHEMA);

        assertThatThrownBy(() -> new PptGenerationService(new InMemoryPptTaskStore(), incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCHEMA");
    }

    @Test
    void runsAllEightStatesInOrderAndReachesSuccess() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, allStates(log));

        long taskId = service.create("conv-1", "帮我做一份介绍 PPT");

        assertThat(log).containsExactly(
                "INIT#1", "REQUIREMENT#1", "SEARCH#1", "TEMPLATE#1", "OUTLINE#1", "SCHEMA#1", "IMAGE#1", "RENDER#1");
        assertThat(service.describe(taskId)).isPresent();
        assertThat(service.describe(taskId).orElseThrow().status()).isEqualTo(PptState.SUCCESS);
        assertThat(service.describe(taskId).orElseThrow().errorMsg()).isNull();
    }

    @Test
    void rejectsNonCreateIntentBeforeTouchingTheTaskStore() {
        List<String> log = new ArrayList<>();
        PptGenerationService service = new PptGenerationService(new InMemoryPptTaskStore(), allStates(log));

        assertThatThrownBy(() -> service.create("conv-1", "继续生成之前那份 PPT"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("RESUME");
        assertThat(log).as("意图识别没通过，不该跑到任何一个状态").isEmpty();
    }

    @Test
    void checkpointStaysOnTheFailingStateAndDoesNotAdvanceOptimistically() {
        List<String> log = new ArrayList<>();
        List<PptGenerationStrategy> strategies = new ArrayList<>();
        for (PptState state : List.of(PptState.INIT, PptState.REQUIREMENT, PptState.SEARCH, PptState.TEMPLATE,
                PptState.OUTLINE, PptState.IMAGE, PptState.RENDER)) {
            strategies.add(new RecordingPptGenerationStrategy(state, log));
        }
        // SCHEMA 永远失败——验证状态机不会把 checkpoint 提前推进到 RENDER
        strategies.add(new RecordingPptGenerationStrategy(PptState.SCHEMA, log, Integer.MAX_VALUE));
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, strategies);

        assertThatThrownBy(() -> service.create("conv-1", "帮我做一份介绍 PPT"))
                .isInstanceOf(PptGenerationException.class)
                .hasMessageContaining("SCHEMA");

        // 只跑到 SCHEMA 就失败了，RENDER 完全没有被调用过——不能因为异常路径而误触发下游状态
        assertThat(log).containsExactly(
                "INIT#1", "REQUIREMENT#1", "SEARCH#1", "TEMPLATE#1", "OUTLINE#1", "SCHEMA#1");

        // create() 抛异常时拿不到返回值——这个测试只创建了一条任务，InMemoryPptTaskStore 的主键
        // 序列从 1 开始，白盒断言用这个已知的第一个 id
        long taskId = 1L;
        PptTask task = taskStore.findById(taskId).orElseThrow();
        assertThat(task.status()).as("checkpoint 必须原地停在失败的状态，不能提前写成下一个状态")
                .isEqualTo(PptState.SCHEMA);
        assertThat(task.errorMsg()).isNotBlank();
    }

    @Test
    void resumingAfterAFailureRerunsOnlyTheFailedStateNotFromInit() {
        List<String> log = new ArrayList<>();
        List<PptGenerationStrategy> strategies = new ArrayList<>();
        for (PptState state : List.of(PptState.INIT, PptState.REQUIREMENT, PptState.SEARCH, PptState.TEMPLATE,
                PptState.OUTLINE, PptState.IMAGE, PptState.RENDER)) {
            strategies.add(new RecordingPptGenerationStrategy(state, log));
        }
        // SCHEMA 第一次调用失败，第二次（也就是恢复重跑）成功
        strategies.add(new RecordingPptGenerationStrategy(PptState.SCHEMA, log, 1));
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, strategies);

        assertThatThrownBy(() -> service.create("conv-1", "帮我做一份介绍 PPT"))
                .isInstanceOf(PptGenerationException.class);
        long taskId = 1L;
        assertThat(taskStore.findById(taskId).orElseThrow().status()).isEqualTo(PptState.SCHEMA);

        log.clear();
        service.run(taskId);

        // 恢复只重跑了 SCHEMA（这次成功）和它之后的 IMAGE/RENDER，之前已经成功过的状态一次都没有重跑
        assertThat(log).containsExactly("SCHEMA#2", "IMAGE#1", "RENDER#1");
        assertThat(taskStore.findById(taskId).orElseThrow().status()).isEqualTo(PptState.SUCCESS);
        assertThat(taskStore.findById(taskId).orElseThrow().errorMsg()).isNull();
    }
}
