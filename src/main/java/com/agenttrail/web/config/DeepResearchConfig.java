package com.agenttrail.web.config;
import com.agenttrail.web.service.AgentLoopExecutorFactory;

import com.agenttrail.loop.context.ContextCompactor;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.capability.deepresearch.DeepResearchService;
import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker;
import com.agenttrail.capability.deepresearch.DeepResearchWorkflow;
import com.agenttrail.capability.deepresearch.InMemoryResearchArtifactStore;
import com.agenttrail.capability.deepresearch.ResearchArtifactStore;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.runtime.repository.CheckpointStore;
import com.agenttrail.runtime.repository.InMemoryCheckpointStore;
import com.agenttrail.runtime.repository.InMemoryRunEventStore;
import com.agenttrail.runtime.repository.RunEventStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DeepResearch（issue #25/#34/#35/#36/#37）的生产装配。模型分两档，不是单一 {@code deepseek-chat}：
 * {@code plainExecutor}（需求澄清/主题生成/plan/critique/综合报告，见 {@link DeepResearchService}
 * 里对应调用点）不挂工具，默认落到更便宜的 {@code qwen-plus}——这几步占了 DeepResearch 绝大多数的
 * LLM 调用次数，省 token 主要靠这里。{@code searchExecutor} 必须真的调用联网搜索工具，默认单独钉在
 * {@code deepseek-chat}：issue #22 联调时发现 qwen-plus 走 {@code spring-ai-openai:2.0.0} 合并流式
 * tool_call chunk 有个未修的第三方库兼容性问题（见踩坑点 #78a），deepseek-chat 不受影响——这一步
 * 不能为了省 token 去踩这个坑，工具调用一旦失败整个检索任务就废了。
 */
@Configuration
public class DeepResearchConfig {

    @Bean
    public DeepResearchService deepResearchService(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.deepresearch.model:qwen-plus}") String modelId,
            @Value("${agenttrail.deepresearch.search-model:deepseek-chat}") String searchModelId,
            @Value("${agenttrail.deepresearch.max-concurrent-tasks-per-layer:3}") int maxConcurrentTasksPerLayer,
            @Value("${agenttrail.deepresearch.max-tasks-per-plan:20}") int maxTasksPerPlan,
            @Value("${agenttrail.deepresearch.max-task-retries:2}") int maxTaskRetries,
            @Value("${agenttrail.deepresearch.max-critique-rounds:3}") int maxCritiqueRounds,
            // 默认沿用 ContextPolicy.DEFAULT_TOKEN_THRESHOLD（60_000）——研究上下文和
            // AgentLoopExecutor 自己单轮 ReAct 历史的膨胀量级类似，没有理由默认给一个不同的阈值
            @Value("${agenttrail.deepresearch.context.token-threshold:60000}") int contextTokenThreshold) {
        // DeepResearch 专用的上下文压缩器（issue #37）——只压缩 critique()/summarize() 用到的
        // 累积检索结果 + 批判反馈这份文本，跟 executorFactory.forModel(...) 返回的执行器各自
        // 内部 ReAct 子循环的上下文压缩是两回事，互不干扰、各自独立配置。压缩本身不调用工具，
        // 跟着 plainExecutor 走同一档便宜模型即可。
        ContextPolicy researchContextPolicy = ContextPolicy.builder()
                .tokenThreshold(contextTokenThreshold)
                .retainLatestOnlyMarkers(DeepResearchService.CRITIQUE_FEEDBACK_MARKER)
                .build();
        ContextCompactor researchContextCompactor = new ContextCompactor(
                researchContextPolicy, executorFactory.chatModelFor(modelId));

        return new DeepResearchService(
                executorFactory.forInternalOrchestration(modelId, false),
                executorFactory.forInternalOrchestration(searchModelId, true),
                maxConcurrentTasksPerLayer, maxTasksPerPlan, maxTaskRetries, maxCritiqueRounds,
                researchContextCompactor);
    }

    /**
     * 一次 DeepResearch 是"需求澄清→主题生成→逐任务检索→综合报告"一整条链路，分钟级操作——
     * 不能占着 HTTP 请求线程等它跑完，理由和 {@code PptGenerationConfig#pptGenerationExecutor}
     * 完全一致：单独命名池，不跟 Tomcat 请求线程或别的阻塞代码共用。
     */
    @Bean(name = "deepResearchExecutor", destroyMethod = "shutdown")
    public ExecutorService deepResearchExecutor(
            @Value("${agenttrail.deepresearch.executor.pool-size:4}") int poolSize,
            @Value("${agenttrail.deepresearch.executor.queue-capacity:20}") int queueCapacity) {
        AtomicInteger threadCount = new AtomicInteger();
        ThreadFactory namedDaemonThreads = runnable -> {
            Thread thread = new Thread(runnable, "deep-research-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity), namedDaemonThreads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public CheckpointStore deepResearchCheckpointStore() {
        return new InMemoryCheckpointStore();
    }

    @Bean
    public RunEventStore deepResearchRunEventStore() {
        return new InMemoryRunEventStore();
    }

    @Bean
    public ResearchArtifactStore deepResearchArtifactStore() {
        return new InMemoryResearchArtifactStore();
    }

    @Bean
    public DeepResearchWorkflow deepResearchWorkflow(DeepResearchService service,
            CheckpointStore checkpoints, ResearchArtifactStore artifacts, RunEventStore events) {
        return new DeepResearchWorkflow(service, checkpoints, artifacts, events);
    }

    @Bean
    public DeepResearchTaskWorker deepResearchTaskWorker(DeepResearchWorkflow workflow,
            AgentTaskManager taskManager, @org.springframework.beans.factory.annotation.Qualifier("deepResearchExecutor")
            ExecutorService executor, RunEventStore events) {
        return new DeepResearchTaskWorker(workflow, taskManager, executor, events);
    }
}
