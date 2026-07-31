package com.agenttrail.loop.deepresearch;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用 {@link ScriptedChatModel} 驱动 plain/search 两个执行器，只验证
 * {@link DeepResearchService} 自己的编排逻辑（该在什么条件下短路、该按什么顺序调哪个执行器、
 * 计划 JSON 怎么解析、分层并发是否真的并发/跨层是否真的串行）——工具调用机制本身已经由
 * {@code AgentLoopExecutor} 自己的测试覆盖，这里的测试替身都不需要真的触发工具调用。
 * 真实联网搜索的端到端验证见 {@code DeepResearchServiceIT}。
 *
 * <p>{@link ScriptedChatModel} 内部用 {@code ArrayDeque}，不是线程安全的——凡是测试同一层
 * 多任务并发的场景，必须换用本文件里的 {@link ConcurrentTrackingChatModel}，不能指望
 * {@code ScriptedChatModel} 在多个虚拟线程并发调用下还能正常工作。跨层场景因为严格串行，
 * 用 {@code ScriptedChatModel} 是安全的。
 */
class DeepResearchServiceTest {

    @Test
    void shortCircuitsWhenTheModelSaysMoreInfoIsNeeded() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("【需要补充信息】\n请问你想研究哪个具体的公司或事件？")));
        ScriptedChatModel searchModel = new ScriptedChatModel();
        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(searchModel, List.of(), 5));

        DeepResearchReport report = service.research("帮我研究一下");

        assertThat(report.needsClarification()).isTrue();
        assertThat(report.clarifyingQuestion()).contains("请问你想研究哪个具体的公司或事件");
        assertThat(report.clarifyingQuestion()).doesNotContain("【需要补充信息】");
        assertThat(searchModel.roundCount()).as("信息不足时不该跑到需要搜索的阶段").isZero();
    }

    @Test
    void fallsBackToKeywordDetectionWhenNoMarkerIsPresent() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("这个问题不够明确，能否说明具体想了解哪个方面？")));
        ScriptedChatModel searchModel = new ScriptedChatModel();
        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(searchModel, List.of(), 5));

        DeepResearchReport report = service.research("随便研究点什么");

        assertThat(report.needsClarification()).isTrue();
    }

    @Test
    void runsTheFullPipelineAcrossTwoSequentialLayersWhenInformationIsSufficient() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("【开始研究】研究某公司近期的市场表现")),
                List.of(text("1. 近期财报表现\n2. 市场竞争格局")),
                List.of(text("""
                        {"tasks":[
                          {"id":"task-1","instruction":"搜索该公司最新财报数据","order":1},
                          {"id":"task-2","instruction":"搜索该公司主要竞争对手动态","order":2}
                        ]}
                        """)),
                List.of(text("# 研究报告\n综合两项任务的结果...")));
        ScriptedChatModel searchModel = new ScriptedChatModel(
                List.of(text("财报显示营收同比增长")),
                List.of(text("主要竞争对手近期发布了新产品")));

        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(searchModel, List.of(), 5));

        DeepResearchReport report = service.research("帮我研究一下某公司");

        assertThat(report.needsClarification()).isFalse();
        assertThat(report.researchTopic()).contains("近期财报表现");
        assertThat(report.taskResults()).hasSize(2);
        assertThat(report.taskResults().get(0).taskId()).isEqualTo("task-1");
        assertThat(report.taskResults().get(0).output()).isEqualTo("财报显示营收同比增长");
        assertThat(report.taskResults().get(1).taskId()).isEqualTo("task-2");
        assertThat(report.report()).isEqualTo("# 研究报告\n综合两项任务的结果...");
        assertThat(plainModel.roundCount()).as("澄清+主题+计划+总结，共 4 次纯文本调用").isEqualTo(4);
        assertThat(searchModel.roundCount()).as("两层各一个任务，各跑一次").isEqualTo(2);

        // 第二层（order=2）的任务应该带上第一层的结果作为依赖上下文
        String secondCallPrompt = searchModel.messagesAtRound(1).stream()
                .map(m -> m.getText())
                .reduce("", String::concat);
        assertThat(secondCallPrompt).contains("财报显示营收同比增长");
    }

    @Test
    void sameLayerTasksRunConcurrentlyButNeverExceedTheConfiguredCap() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("【开始研究】方向已明确")),
                List.of(text("1. 分析点")),
                List.of(text("""
                        {"tasks":[
                          {"id":"task-1","instruction":"搜索1","order":1},
                          {"id":"task-2","instruction":"搜索2","order":1},
                          {"id":"task-3","instruction":"搜索3","order":1},
                          {"id":"task-4","instruction":"搜索4","order":1}
                        ]}
                        """)),
                List.of(text("# 报告")));
        ConcurrentTrackingChatModel searchModel = new ConcurrentTrackingChatModel(Duration.ofMillis(300));

        int concurrencyCap = 3;
        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(searchModel, List.of(), 5),
                concurrencyCap, 20);

        DeepResearchReport report = service.research("测试并发上限");

        assertThat(report.taskResults()).hasSize(4);
        assertThat(searchModel.maxObservedConcurrency())
                .as("4 个同层任务应该真的并发跑，但不能超过配置的上限 %d", concurrencyCap)
                .isGreaterThan(1)
                .isLessThanOrEqualTo(concurrencyCap);
    }

    @Test
    void truncatesThePlanWhenItExceedsTheConfiguredBreadthCap() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("【开始研究】方向已明确")),
                List.of(text("1. 分析点")),
                List.of(text("""
                        {"tasks":[
                          {"id":"task-1","instruction":"搜索1","order":1},
                          {"id":"task-2","instruction":"搜索2","order":1},
                          {"id":"task-3","instruction":"搜索3","order":1}
                        ]}
                        """)),
                List.of(text("# 报告")));
        ScriptedChatModel searchModel = new ScriptedChatModel(
                List.of(text("结果1")),
                List.of(text("结果2")));

        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(searchModel, List.of(), 5),
                3, 2);

        DeepResearchReport report = service.research("测试广度上限");

        assertThat(report.taskResults())
                .as("计划给了 3 个任务，上限是 2，应该按原始顺序截断到前 2 个")
                .hasSize(2);
        assertThat(report.taskResults()).extracting(TaskResult::taskId).containsExactly("task-1", "task-2");
    }

    @Test
    void retriesAFailingTaskAndSucceedsOnceItRecovers() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("【开始研究】方向已明确")),
                List.of(text("1. 分析点")),
                List.of(text("""
                        {"tasks":[{"id":"task-1","instruction":"搜索1","order":1}]}
                        """)),
                List.of(text("# 报告")));
        FlakyChatModel searchModel = new FlakyChatModel(1); // 第一次失败，第二次（重试）成功

        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(searchModel, List.of(), 5),
                3, 20, 2);

        DeepResearchReport report = service.research("测试重试成功");

        assertThat(report.taskResults()).hasSize(1);
        TaskResult result = report.taskResults().get(0);
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("success after retry");
        assertThat(searchModel.callCount()).as("失败 1 次 + 成功 1 次，共调用 2 次").isEqualTo(2);
    }

    @Test
    void givesUpAfterExhaustingRetriesButDoesNotBlockOtherTasksOrTheOverallReport() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("【开始研究】方向已明确")),
                List.of(text("1. 分析点")),
                List.of(text("""
                        {"tasks":[{"id":"task-1","instruction":"搜索1","order":1}]}
                        """)),
                List.of(text("# 报告：任务失败但依然生成了总结")));
        FlakyChatModel alwaysFailingModel = new FlakyChatModel(Integer.MAX_VALUE); // 永远失败

        int maxTaskRetries = 2;
        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(alwaysFailingModel, List.of(), 5),
                3, 20, maxTaskRetries);

        DeepResearchReport report = service.research("测试重试耗尽");

        assertThat(report.taskResults()).hasSize(1);
        TaskResult result = report.taskResults().get(0);
        assertThat(result.success()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(alwaysFailingModel.callCount())
                .as("1 次初始尝试 + %d 次重试，达到上限后不再继续", maxTaskRetries)
                .isEqualTo(maxTaskRetries + 1);
        assertThat(report.report()).as("单个任务失败不该阻塞总结阶段").isEqualTo("# 报告：任务失败但依然生成了总结");
    }

    @Test
    void throwsAClearErrorWhenThePlanIsNotValidJson() {
        ScriptedChatModel plainModel = new ScriptedChatModel(
                List.of(text("【开始研究】方向已明确")),
                List.of(text("1. 分析点")),
                List.of(text("这不是合法的 JSON")));
        ScriptedChatModel searchModel = new ScriptedChatModel();

        DeepResearchService service = new DeepResearchService(
                new AgentLoopExecutor(plainModel, List.of(), 5),
                new AgentLoopExecutor(searchModel, List.of(), 5));

        assertThatThrownBy(() -> service.research("测试问题"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("执行计划解析失败");
    }

    /**
     * 线程安全的 {@link ChatModel} 测试替身，只用来验证"同一层任务确实并发跑、且并发数不超过
     * 配置的上限"——不关心每次调用具体返回什么内容，靠 {@link AtomicInteger} 记录同时在跑的
     * 调用数峰值。每次调用固定睡 {@code workDuration}，让并发窗口足够宽，能稳定观察到重叠。
     */
    private static final class ConcurrentTrackingChatModel implements ChatModel {

        private final Duration workDuration;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxObservedConcurrency = new AtomicInteger();

        ConcurrentTrackingChatModel(Duration workDuration) {
            this.workDuration = workDuration;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("只支持 stream()");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Mono.fromRunnable(() -> {
                        int current = active.incrementAndGet();
                        maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, current));
                    })
                    .then(Mono.delay(workDuration))
                    .doFinally(signal -> active.decrementAndGet())
                    .map(ignored -> (ChatResponse) new ChatResponse(
                            List.of(new org.springframework.ai.chat.model.Generation(
                                    AssistantMessage.builder().content("done").build()))))
                    .flux();
        }

        int maxObservedConcurrency() {
            return maxObservedConcurrency.get();
        }
    }

    /**
     * {@link ChatModel} 测试替身：前 {@code failuresBeforeSuccess} 次调用让上游 Flux 直接
     * {@code onError}，之后（如果还有）才成功——用来驱动 issue #36 的重试逻辑，不需要真的
     * 接网络就能确定性地验证"失败第 N 次后还会重试第 N+1 次"。
     */
    private static final class FlakyChatModel implements ChatModel {

        private final int failuresBeforeSuccess;
        private final AtomicInteger callCount = new AtomicInteger();

        FlakyChatModel(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("只支持 stream()");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int attempt = callCount.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return Flux.error(new RuntimeException("simulated transient failure #" + attempt));
            }
            return Flux.just(text("success after retry"));
        }

        int callCount() {
            return callCount.get();
        }
    }
}
