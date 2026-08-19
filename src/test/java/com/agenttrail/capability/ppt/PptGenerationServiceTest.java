package com.agenttrail.capability.ppt;

import com.agenttrail.capability.ppt.support.RecordingPptGenerationStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        for (PptState state : List.of(PptState.INIT, PptState.CLARIFY, PptState.REQUIREMENT, PptState.SEARCH, PptState.VISUAL_PLAN, PptState.TEMPLATE,
                PptState.OUTLINE, PptState.SCHEMA, PptState.IMAGE, PptState.RENDER, PptState.VERIFY)) {
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
    void runsEveryStateInOrderAndReachesSuccess() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, allStates(log));

        long taskId = service.create("conv-1", "帮我做一份介绍 PPT");

        assertThat(log).containsExactly(
                "INIT#1", "CLARIFY#1", "REQUIREMENT#1", "SEARCH#1", "VISUAL_PLAN#1", "TEMPLATE#1", "OUTLINE#1", "SCHEMA#1",
                "IMAGE#1", "RENDER#1", "VERIFY#1");
        assertThat(service.describe(taskId)).isPresent();
        assertThat(service.describe(taskId).orElseThrow().status()).isEqualTo(PptState.SUCCESS);
        assertThat(service.describe(taskId).orElseThrow().runStatus()).isEqualTo(PptRunStatus.SUCCEEDED);
        // 初次执行先 claim 一次，再为 9 个阶段各提交一次 checkpoint。
        assertThat(service.describe(taskId).orElseThrow().revision()).isEqualTo(12);
        assertThat(service.describe(taskId).orElseThrow().errorMsg()).isNull();
        assertThat(service.runningTaskIdsFor("legacy")).isEmpty();
    }

    @Test
    void cancellationIsAppliedAtTheNextStateBoundary() {
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        long taskId = taskStore.create("user-1", "conv-1", PptGenerationContext.initial("conv-1", "做一份 PPT"));
        List<String> log = new ArrayList<>();
        List<PptGenerationStrategy> strategies = allStates(log);
        strategies.removeIf(strategy -> strategy.handledState() == PptState.INIT);
        strategies.add(new PptGenerationStrategy() {
            @Override
            public PptState handledState() {
                return PptState.INIT;
            }

            @Override
            public PptGenerationContext execute(PptGenerationContext context) {
                log.add("INIT#1");
                taskStore.requestCancel(taskId);
                return context;
            }
        });
        PptGenerationService service = new PptGenerationService(taskStore, strategies);

        service.run(taskId);

        assertThat(taskStore.findById(taskId).orElseThrow().status()).isEqualTo(PptState.CANCELLED);
        assertThat(log).containsExactly("INIT#1");
        assertThat(service.runningTaskIdsFor("user-1")).isEmpty();
    }

    @Test
    void recoveryCoordinatorRequeuesPersistedQueuedTaskThroughTheNormalService() {
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        long taskId = taskStore.create("user-1", "conv-recovery",
                PptGenerationContext.initial("conv-recovery", "做一份 PPT"));
        PptGenerationService service = new PptGenerationService(taskStore,
                allStates(new ArrayList<>()), new com.agenttrail.runtime.lifecycle.InMemoryLeaseManager(),
                new PptRetryPolicy(2, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(1)));
        PptRecoveryCoordinator recovery = new PptRecoveryCoordinator(taskStore, service, Runnable::run, 10, 0);

        assertThat(recovery.recoverOnce()).isEqualTo(1);
        assertThat(taskStore.findById(taskId).orElseThrow().runStatus()).isEqualTo(PptRunStatus.SUCCEEDED);
    }

    @Test
    void explicitResumeContinuesFromCheckpointedStateNotFromInit() {
        List<String> log = new ArrayList<>();
        List<PptGenerationStrategy> strategies = new ArrayList<>();
        for (PptState state : List.of(PptState.INIT, PptState.CLARIFY, PptState.REQUIREMENT, PptState.SEARCH, PptState.VISUAL_PLAN, PptState.TEMPLATE,
                PptState.OUTLINE, PptState.IMAGE, PptState.RENDER, PptState.VERIFY)) {
            strategies.add(new RecordingPptGenerationStrategy(state, log));
        }
        // SCHEMA 第一次调用失败，第二次（通过 RESUME 意图触发的续跑）成功
        strategies.add(new RecordingPptGenerationStrategy(PptState.SCHEMA, log, 1));
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, strategies);

        assertThatThrownBy(() -> service.create("conv-1", "帮我做一份介绍 PPT"))
                .isInstanceOf(PptGenerationException.class);
        long taskId = 1L;
        assertThat(taskStore.findById(taskId).orElseThrow().status()).isEqualTo(PptState.SCHEMA);

        log.clear();
        service.run(taskId);

        // 只重跑了 SCHEMA（这次成功）和它之后的 IMAGE/RENDER，INIT~OUTLINE 一次都没有重跑
        assertThat(log).containsExactly("SCHEMA#2", "IMAGE#1", "RENDER#1", "VERIFY#1");
        assertThat(taskStore.findById(taskId).orElseThrow().status()).isEqualTo(PptState.SUCCESS);
    }

    @Test
    void explicitModifyOnlyRerunsFromSchemaOnward() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, allStates(log));

        // 手工造一条"已经跑完"的任务，模拟一次真实 CREATE 已经产出的 REQUIREMENT/SEARCH/
        // TEMPLATE/OUTLINE 内容——不通过 service.create 跑（RecordingPptGenerationStrategy 不会
        // 真的往 context 里填内容），这样才能在断言里区分"这些内容是复用来的"还是"重新生成的"
        PptRequirement requirement = new PptRequirement("标题", "主题", "受众", 3, "专业简洁");
        PptOutline outline = new PptOutline("封面主标题", "封面副标题",
                List.of(new PptOutlineSlide("t1", List.of("b1"))));
        PptGenerationContext original = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(requirement)
                .withSearchMaterials(List.of("素材1"))
                .withTemplatePath("template.pptx")
                .withOutline(outline)
                .withSchema(new PptSchema("旧标题", "旧副标题", List.of()))
                .withOutputPath("old-output.pptx");
        long originalTaskId = taskStore.create("conv-1", original);
        taskStore.advance(originalTaskId, PptState.SUCCESS, original);

        long modifiedTaskId = service.prepareModify("legacy", originalTaskId, "帮我修改这个标题", null);
        service.run(modifiedTaskId);

        assertThat(modifiedTaskId).as("MODIFY 新建一条任务，不覆盖原任务").isNotEqualTo(originalTaskId);
        // 只有 SCHEMA/IMAGE/RENDER 被真正执行过——INIT/REQUIREMENT/SEARCH/TEMPLATE/OUTLINE
        // 一次都没跑，证明 MODIFY 不是重新走一遍完整流程
        assertThat(log).containsExactly("SCHEMA#1", "IMAGE#1", "RENDER#1", "VERIFY#1");

        PptTask modifiedTask = taskStore.findById(modifiedTaskId).orElseThrow();
        assertThat(modifiedTask.status()).isEqualTo(PptState.SUCCESS);
        PptGenerationContext modifiedContext = PptContextJson.fromJson(modifiedTask.contextJson());
        assertThat(modifiedContext.requirement()).as("复用原任务的 REQUIREMENT 产出").isEqualTo(requirement);
        assertThat(modifiedContext.searchMaterials()).as("复用原任务的 SEARCH 产出").containsExactly("素材1");
        assertThat(modifiedContext.templatePath()).as("复用原任务的 TEMPLATE 产出").isEqualTo("template.pptx");
        assertThat(modifiedContext.outline()).as("复用原任务的 OUTLINE 产出").isEqualTo(outline);
        assertThat(modifiedContext.userRequirement()).as("修改指令要传给 SCHEMA 状态")
                .isEqualTo("帮我修改这个标题");

        // 原任务原地保留，不受这次 MODIFY 影响
        PptGenerationContext originalContextAfterModify =
                PptContextJson.fromJson(taskStore.findById(originalTaskId).orElseThrow().contextJson());
        assertThat(originalContextAfterModify.outputPath()).isEqualTo("old-output.pptx");
    }

    @Test
    void explicitModifyUsesTheRequestedBaseTaskAndPersistsVersionMetadata() {
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, allStates(new ArrayList<>()));
        PptGenerationContext baseContext = PptGenerationContext.initial("conv-1", "初始 PPT")
                .withArtifactRef(new PptArtifactRef("ppt-artifact-base", "ppt/artifacts/base.pptx", "checksum", 10,
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        long baseTaskId = taskStore.create("user-a", "conv-1", baseContext);
        taskStore.advance(baseTaskId, PptState.SUCCESS, baseContext);

        long modifiedTaskId = service.prepareModify("user-a", baseTaskId, "改第二页", "modify-key");
        PptGenerationContext modified = PptContextJson.fromJson(taskStore.findById(modifiedTaskId).orElseThrow().contextJson());

        assertThat(modified.operation()).isEqualTo("MODIFY");
        assertThat(modified.baseTaskId()).isEqualTo(baseTaskId);
        assertThat(modified.baseArtifactId()).isEqualTo("ppt-artifact-base");
        assertThat(modified.schema()).isEqualTo(baseContext.schema());
    }

    @Test
    void activeTaskReplacementKeepsTheOriginalRequirementAndStartsFromInit() {
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, allStates(new ArrayList<>()));
        PptGenerationContext baseContext = PptGenerationContext.initial("conv-1", "季度经营复盘");
        long baseTaskId = taskStore.create("user-a", "conv-1", baseContext);
        taskStore.advance(baseTaskId, PptState.IMAGE, baseContext);

        long replacementId = service.prepareReplacement("user-a", baseTaskId,
                "第二页改成流程图", "replacement-key");
        PptTask replacementTask = taskStore.findById(replacementId).orElseThrow();
        PptGenerationContext replacement = PptContextJson.fromJson(replacementTask.contextJson());

        assertThat(replacementTask.status()).isEqualTo(PptState.INIT);
        assertThat(replacement.operation()).isEqualTo("MODIFY");
        assertThat(replacement.baseTaskId()).isEqualTo(baseTaskId);
        assertThat(replacement.userRequirement()).contains("季度经营复盘", "第二页改成流程图");
    }

    @Test
    void checkpointStaysOnTheFailingStateAndDoesNotAdvanceOptimistically() {
        List<String> log = new ArrayList<>();
        List<PptGenerationStrategy> strategies = new ArrayList<>();
        for (PptState state : List.of(PptState.INIT, PptState.CLARIFY, PptState.REQUIREMENT, PptState.SEARCH, PptState.VISUAL_PLAN, PptState.TEMPLATE,
                PptState.OUTLINE, PptState.IMAGE, PptState.RENDER, PptState.VERIFY)) {
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
                "INIT#1", "CLARIFY#1", "REQUIREMENT#1", "SEARCH#1", "VISUAL_PLAN#1", "TEMPLATE#1", "OUTLINE#1", "SCHEMA#1");

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
        for (PptState state : List.of(PptState.INIT, PptState.CLARIFY, PptState.REQUIREMENT, PptState.SEARCH, PptState.VISUAL_PLAN, PptState.TEMPLATE,
                PptState.OUTLINE, PptState.IMAGE, PptState.RENDER, PptState.VERIFY)) {
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
        assertThat(log).containsExactly("SCHEMA#2", "IMAGE#1", "RENDER#1", "VERIFY#1");
        assertThat(taskStore.findById(taskId).orElseThrow().status()).isEqualTo(PptState.SUCCESS);
        assertThat(taskStore.findById(taskId).orElseThrow().errorMsg()).isNull();
    }

    @Test
    void concurrentResumeRunsOnlyOneWorkerForTheSameTask() throws Exception {
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        AtomicInteger initCalls = new AtomicInteger();
        CountDownLatch initEntered = new CountDownLatch(1);
        CountDownLatch allowInitToFinish = new CountDownLatch(1);
        List<PptGenerationStrategy> strategies = new ArrayList<>(allStates(new ArrayList<>()));
        strategies.removeIf(strategy -> strategy.handledState() == PptState.INIT);
        strategies.add(new BlockingInitStrategy(initCalls, initEntered, allowInitToFinish));

        PptGenerationService firstWorker = new PptGenerationService(taskStore, strategies);
        PptGenerationService secondWorker = new PptGenerationService(taskStore, strategies);
        long taskId = taskStore.create("user-1", "conv-1", PptGenerationContext.initial("conv-1", "make a deck"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstRun = executor.submit(() -> firstWorker.run(taskId));
            assertThat(initEntered.await(5, TimeUnit.SECONDS)).isTrue();

            secondWorker.run(taskId);
            assertThat(initCalls).hasValue(1);

            allowInitToFinish.countDown();
            firstRun.get(5, TimeUnit.SECONDS);
            assertThat(taskStore.findById(taskId).orElseThrow().status()).isEqualTo(PptState.SUCCESS);
        } finally {
            allowInitToFinish.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * CLARIFY 判定信息不足时，任务必须停在 AWAITING_INPUT：不往下跑、不记 errorMsg。
     *
     * <p>"不记 errorMsg" 是这条用例真正在守的东西——等人和跑挂了在协议层必须分得开，
     * 一旦把追问写进 errorMsg，前端会把一次正常的追问渲染成一条报错，
     * runningTaskIdsFor 也会把它当成出错任务筛掉，用户就再没有回答的入口了。
     */
    @Test
    void parksTheTaskAtAwaitingInputWhenClarifyAsksAQuestionInsteadOfFailingIt() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, askingClarify(log, "要做什么主题的 PPT？"));

        long taskId = service.create("conv-1", "什么情况");

        assertThat(log).containsExactly("INIT#1", "CLARIFY#1");
        PptTask task = service.describe(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.AWAITING_INPUT);
        assertThat(task.errorMsg()).isNull();
        assertThat(service.runningTaskIdsFor("legacy")).isEmpty();
    }

    /** 回答后必须重新采集并校验主题，不能因为用户回答过一次就无条件开工。 */
    @Test
    void answeringTheClarificationRunsClarifyAgainBeforeStartingWork() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, askingClarify(log, "要做什么主题的 PPT？"));
        long taskId = service.create("conv-1", "什么情况");

        service.answerClarification("legacy", taskId, "讲一下我们团队三季度的复盘");
        service.run(taskId);

        assertThat(log).containsExactly(
                "INIT#1", "CLARIFY#1", "CLARIFY#2", "REQUIREMENT#1", "SEARCH#1", "VISUAL_PLAN#1", "TEMPLATE#1", "OUTLINE#1", "SCHEMA#1",
                "IMAGE#1", "RENDER#1", "VERIFY#1");
        assertThat(service.describe(taskId).orElseThrow().status()).isEqualTo(PptState.SUCCESS);
    }

    /** 追问、回答、原始需求三段都要带给 REQUIREMENT：只喂回答的话，"讲三季度复盘"这种补充脱离追问就不知所云。 */
    @Test
    void combinesOriginalRequirementQuestionAndAnswerIntoTheRequirementHandedDownstream() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, askingClarify(log, "要做什么主题？"));
        long taskId = service.create("conv-1", "什么情况");

        service.answerClarification("legacy", taskId, "讲三季度复盘");

        PptTask task = service.describe(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.CLARIFY);
        assertThat(task.contextJson()).contains("什么情况").contains("要做什么主题？").contains("讲三季度复盘");
    }

    @Test
    void cannotAdvancePastRequirementWithoutAValidTopic() {
        List<String> log = new ArrayList<>();
        List<PptGenerationStrategy> strategies = allStates(log);
        strategies.removeIf(strategy -> strategy.handledState() == PptState.REQUIREMENT);
        strategies.add(new PptGenerationStrategy() {
            @Override
            public PptState handledState() {
                return PptState.REQUIREMENT;
            }

            @Override
            public PptGenerationContext execute(PptGenerationContext context) {
                log.add("REQUIREMENT#1");
                return context.withRequirement(null)
                        .withClarifyingQuestion("这份 PPT 主要想讲什么主题？");
            }
        });
        PptGenerationService service = new PptGenerationService(new InMemoryPptTaskStore(), strategies);

        long taskId = service.create("conv-1", "给管理层看，控制在 8 页");

        assertThat(service.describe(taskId).orElseThrow().status()).isEqualTo(PptState.AWAITING_INPUT);
        assertThat(log).containsExactly("INIT#1", "CLARIFY#1", "REQUIREMENT#1");
    }

    /** 没在等人的任务收到"回答"，那其实是一句新需求——静默拿它覆盖掉正在跑的任务比报错难查得多。 */
    @Test
    void rejectsAnAnswerWhenTheTaskIsNotWaitingForOne() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, allStates(log));
        long taskId = service.create("conv-1", "做一份复盘 PPT");

        assertThatThrownBy(() -> service.answerClarification("legacy", taskId, "补充一句"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有在等待补充信息");
    }

    /** 停在 AWAITING_INPUT 的任务点"继续"是空转：run() 立刻返回，不会去找一个并不存在的 Strategy。 */
    @Test
    void resumingAnAwaitingTaskIsANoOpRatherThanAMissingStrategyCrash() {
        List<String> log = new ArrayList<>();
        InMemoryPptTaskStore taskStore = new InMemoryPptTaskStore();
        PptGenerationService service = new PptGenerationService(taskStore, askingClarify(log, "要做什么主题？"));
        long taskId = service.create("conv-1", "什么情况");

        service.run(taskId);

        assertThat(log).containsExactly("INIT#1", "CLARIFY#1");
        assertThat(service.describe(taskId).orElseThrow().status()).isEqualTo(PptState.AWAITING_INPUT);
    }

    /** 全量状态，但 CLARIFY 换成一个固定追问的替身。 */
    private static List<PptGenerationStrategy> askingClarify(List<String> log, String question) {
        List<PptGenerationStrategy> strategies = allStates(log);
        strategies.removeIf(strategy -> strategy.handledState() == PptState.CLARIFY);
        strategies.add(new PptGenerationStrategy() {
            private int calls;

            @Override
            public PptState handledState() {
                return PptState.CLARIFY;
            }

            @Override
            public PptGenerationContext execute(PptGenerationContext context) {
                log.add("CLARIFY#" + (++calls));
                return calls == 1 ? context.withClarifyingQuestion(question)
                        : context.withClarifyingQuestion(null);
            }
        });
        return strategies;
    }

    private static final class BlockingInitStrategy implements PptGenerationStrategy {
        private final AtomicInteger calls;
        private final CountDownLatch entered;
        private final CountDownLatch finish;

        private BlockingInitStrategy(AtomicInteger calls, CountDownLatch entered, CountDownLatch finish) {
            this.calls = calls;
            this.entered = entered;
            this.finish = finish;
        }

        @Override
        public PptState handledState() {
            return PptState.INIT;
        }

        @Override
        public PptGenerationContext execute(PptGenerationContext context) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PptGenerationException("interrupted while holding the test lease");
            }
            return context;
        }
    }
}
