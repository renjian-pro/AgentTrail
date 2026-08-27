package com.agenttrail.web.config;
import com.agenttrail.web.service.AgentLoopExecutorFactory;

import com.agenttrail.loop.context.ContextCompactor;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.platform.model.AgentModelProperties;
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
 * DeepResearch（issue #25/#34/#35/#36/#37）的生产装配。规划、检索、总结、写作和反思统一使用
 * {@link AgentModelProperties} 中的文本模型；能力差异只由是否挂载搜索工具决定，不再维护第二套
 * 模型配置。
 */
@Configuration
public class DeepResearchConfig {

    @Bean
    public DeepResearchService deepResearchService(AgentLoopExecutorFactory executorFactory,
            AgentModelProperties modelProperties,
            @Value("${agenttrail.deepresearch.max-concurrent-tasks-per-layer:3}") int maxConcurrentTasksPerLayer,
            @Value("${agenttrail.deepresearch.max-tasks-per-plan:20}") int maxTasksPerPlan,
            @Value("${agenttrail.deepresearch.max-task-retries:2}") int maxTaskRetries,
            @Value("${agenttrail.deepresearch.max-critique-rounds:3}") int maxCritiqueRounds,
            // 默认沿用 ContextPolicy.DEFAULT_TOKEN_THRESHOLD（60_000）——研究上下文和
            // AgentLoopExecutor 自己单轮 ReAct 历史的膨胀量级类似，没有理由默认给一个不同的阈值
            @Value("${agenttrail.deepresearch.context.token-threshold:60000}") int contextTokenThreshold) {
        String modelId = modelProperties.id();
        // DeepResearch 专用的上下文压缩器（issue #37）——只压缩 critique()/summarize() 用到的
        // 累积检索结果 + 批判反馈这份文本，跟 executorFactory.forModel(...) 返回的执行器各自
        // 内部 ReAct 子循环的上下文压缩是两回事，互不干扰。压缩本身不调用工具，但仍复用统一模型，
        // 不再为这一条内部链路单设模型配置。
        ContextPolicy researchContextPolicy = ContextPolicy.builder()
                .tokenThreshold(contextTokenThreshold)
                .retainLatestOnlyMarkers(DeepResearchService.CRITIQUE_FEEDBACK_MARKER)
                .build();
        ContextCompactor researchContextCompactor = new ContextCompactor(
                researchContextPolicy, executorFactory.chatModelFor(modelId));

        return new DeepResearchService(
                executorFactory.forInternalOrchestration(modelId, false),
                executorFactory.forInternalOrchestration(modelId, true),
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

    /**
     * 工作流步骤之间交接报告用，**只活在一次运行内部**（`DeepResearchWorkflow` save → find）。
     * 完成的报告由 {@code CapabilityConversationService} 落进 {@code agent_session.timeline}，
     * 历史回放读的是那一份，所以这里没必要落库（issue #108 讨论后的结论）。
     */
    @Bean
    public ResearchArtifactStore deepResearchArtifactStore() {
        return new InMemoryResearchArtifactStore();
    }

    /**
     * 任务元信息（issue #108 / R20）。配了数据源就落库，否则退化成内存——后者只是让不带
     * 数据库的装配能启动，它当然不解决"重启后 404"那个问题。
     */
    @Bean
    public com.agenttrail.capability.deepresearch.ResearchTaskRecordStore researchTaskRecordStore(
            org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSource) {
        javax.sql.DataSource resolved = dataSource.getIfAvailable();
        return resolved == null
                ? new com.agenttrail.capability.deepresearch.InMemoryResearchTaskRecordStore()
                : new com.agenttrail.capability.deepresearch.JdbcResearchTaskRecordStore(resolved);
    }

    /**
     * 启动扫描：把上一个进程留下的 RUNNING 记录一律标成"被重启打断"（issue #108 / R20）。
     *
     * <p>进程都没了，那些任务不可能再自己推进——留着 RUNNING 会让前端一直轮询一个永远不会变的
     * 状态，比 404 更难判断。这里给出一个诚实的终态，用户看得懂发生了什么、也知道该重新发起。
     *
     * <p>**明确不做续跑**：{@code DeepResearchService#research} 是一次不带 checkpoint 的单体调用，
     * 要做到 PPT 那种"凭 taskId 继续"得先把它拆成状态机，是另一个量级的工作。
     *
     * <p>单实例部署下这个全表扫描是安全的。将来真要多实例，得先有实例标识或租约才能判断
     * "这条 RUNNING 是不是我的"，否则一个实例启动会把另一个实例正在跑的任务标成失败。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner researchTaskRestartSweeper(
            com.agenttrail.capability.deepresearch.ResearchTaskRecordStore records) {
        return args -> {
            int interrupted = records.markRunningAsInterrupted();
            if (interrupted > 0) {
                org.slf4j.LoggerFactory.getLogger(DeepResearchConfig.class)
                        .info("启动扫描：{} 个深度研究任务被上次重启打断，已标记为失败", interrupted);
            }
        };
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
