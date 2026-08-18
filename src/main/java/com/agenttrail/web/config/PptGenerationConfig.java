package com.agenttrail.web.config;
import com.agenttrail.web.service.AgentLoopExecutorFactory;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.capability.ppt.JdbcPptTaskStore;
import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptGenerationMetrics;
import com.agenttrail.capability.ppt.PptRetryPolicy;
import com.agenttrail.capability.ppt.PptCancellationRegistry;
import com.agenttrail.capability.ppt.PptRecoveryCoordinator;
import com.agenttrail.capability.ppt.PptPythonRenderer;
import com.agenttrail.capability.ppt.PptTaskStore;
import com.agenttrail.capability.ppt.InMemoryPptTemplateRegistry;
import com.agenttrail.capability.ppt.PptTemplateChecksum;
import com.agenttrail.capability.ppt.PptTemplateRegistry;
import com.agenttrail.capability.ppt.PptTemplateVersion;
import com.agenttrail.capability.ppt.PptArtifactStore;
import com.agenttrail.capability.ppt.LocalPptArtifactStore;
import com.agenttrail.capability.ppt.image.DashScopeImageClient;
import com.agenttrail.capability.ppt.image.MinioPptImageStore;
import com.agenttrail.capability.ppt.image.PptImageStore;
import com.agenttrail.capability.ppt.image.TextToImageClient;
import com.agenttrail.capability.ppt.strategy.ImageStrategy;
import com.agenttrail.capability.ppt.strategy.ClarifyStrategy;
import com.agenttrail.capability.ppt.strategy.InitStrategy;
import com.agenttrail.capability.ppt.strategy.OutlineStrategy;
import com.agenttrail.capability.ppt.strategy.RenderStrategy;
import com.agenttrail.capability.ppt.strategy.RequirementStrategy;
import com.agenttrail.capability.ppt.strategy.SchemaStrategy;
import com.agenttrail.capability.ppt.strategy.SearchStrategy;
import com.agenttrail.capability.ppt.strategy.TemplateStrategy;
import com.agenttrail.capability.ppt.strategy.VerifyStrategy;
import com.agenttrail.runtime.lifecycle.InMemoryLeaseManager;
import com.agenttrail.runtime.lifecycle.LeaseManager;
import com.agenttrail.infrastructure.lease.RedisLeaseManager;
import com.agenttrail.loop.task.RedisTaskLock;
import io.minio.MinioClient;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.OkHttpClient;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * PPT 生成状态机（issue #24）的生产装配。REQUIREMENT/OUTLINE/SCHEMA 三个状态共用同一个不挂
 * 任何工具的 {@link AgentLoopExecutor}（issue #20 多模型路由里已经装配好的执行器，默认
 * {@code deepseek-chat}，和 {@code DeepResearchConfig} 同样的理由——这几步都是纯文本/结构化输出
 * 生成，不需要工具）。
 *
 * <p>Python 渲染脚本路径、模板文件路径、渲染超时时间全部走配置（{@code agenttrail.ppt.*}），
 * 不写死本地绝对路径——issue #24 明确要求避免的第二个反面案例。默认值是相对于项目根目录的
 * 相对路径，和这个项目当前"本机开发、`mvn` 系列命令都从仓库根目录跑"的实际使用方式一致。
 */
@Configuration
public class PptGenerationConfig {

    @Bean
    public PptTaskStore pptTaskStore(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcPptTaskStore(dataSource);
    }

    @Bean
    public LeaseManager pptLeaseManager(ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            return new InMemoryLeaseManager();
        }
        RedisTaskLock lock = new RedisTaskLock(redisson, "ppt-" + UUID.randomUUID(), Duration.ofMinutes(10));
        lock.startAutoRenewal();
        return new RedisLeaseManager(lock);
    }

    /**
     * 生成一次 PPT 要跑完 8 个状态（含至少一次 LLM 调用、真实文生图请求、Python 子进程渲染），
     * 是分钟级的操作——不能占着 HTTP 请求线程等它跑完（issue #24 的注释里也明确写了这是有意
     * 留到后面做的事）。单独开一个命名池，和 {@code ToolCallExecutor} 给工具执行开专属调度器
     * 是同一个理由：不跟 Tomcat 的请求线程池或别的阻塞代码共用，互不排队。
     */
    @Bean(name = "pptGenerationExecutor", destroyMethod = "shutdown")
    public ExecutorService pptGenerationExecutor(
            @Value("${agenttrail.ppt.executor.pool-size:4}") int poolSize,
            @Value("${agenttrail.ppt.executor.queue-capacity:20}") int queueCapacity) {
        AtomicInteger threadCount = new AtomicInteger();
        ThreadFactory namedDaemonThreads = runnable -> {
            Thread thread = new Thread(runnable, "ppt-generation-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity), namedDaemonThreads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 启动和定时恢复只扫描有限 taskId，再复用同一执行器；恢复器不直接改状态，
     * 防止与正常请求产生两套不一致的状态推进逻辑。
     */
    @Bean
    public PptRecoveryCoordinator pptRecoveryCoordinator(PptTaskStore pptTaskStore,
            PptGenerationService pptGenerationService,
            @Qualifier("pptGenerationExecutor") ExecutorService pptGenerationExecutor,
            @Value("${agenttrail.ppt.recovery.batch-size:20}") int batchSize,
            @Value("${agenttrail.ppt.recovery.jitter-ms:250}") long jitterMillis) {
        return new PptRecoveryCoordinator(pptTaskStore, pptGenerationService, pptGenerationExecutor,
                batchSize, jitterMillis);
    }

    @Bean(name = "pptRenderExecutor", destroyMethod = "shutdown")
    public ExecutorService pptRenderExecutor(
            @Value("${agenttrail.ppt.render.executor.pool-size:2}") int poolSize,
            @Value("${agenttrail.ppt.render.executor.queue-capacity:8}") int queueCapacity) {
        AtomicInteger threadCount = new AtomicInteger();
        ThreadFactory namedDaemonThreads = runnable -> {
            Thread thread = new Thread(runnable, "ppt-render-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity), namedDaemonThreads, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public PptPythonRenderer pptPythonRenderer(
            @Value("${agenttrail.ppt.python-executable:python}") String pythonExecutable,
            @Value("${agenttrail.ppt.render-script-path:src/main/resources/ppt-scripts/render_ppt.py}")
            String renderScriptPath,
            @Value("${agenttrail.ppt.render-timeout-seconds:60}") long renderTimeoutSeconds) {
        return new PptPythonRenderer(pythonExecutable, renderScriptPath, renderTimeoutSeconds);
    }

    @Bean
    public InitStrategy pptInitStrategy() {
        return new InitStrategy();
    }

    /**
     * CLARIFY 状态：不挂工具（{@code forInternalOrchestration(id, false)}），它只做清晰度判定，
     * 挂上联网搜索反而会诱导模型去"查一查再判断"，把一次本该 1 秒返回的判定拖成一轮检索。
     */
    @Bean
    public ClarifyStrategy pptClarifyStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new ClarifyStrategy(executorFactory.forInternalOrchestration(modelId, false));
    }

    @Bean
    public RequirementStrategy pptRequirementStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new RequirementStrategy(executorFactory.forInternalOrchestration(modelId, false));
    }

    /**
     * SEARCH 状态（issue #29）：必须是挂了联网搜索工具的执行器（{@code forModel(id, true)}），
     * 和 {@code DeepResearchConfig#deepResearchService} 里 {@code searchExecutor} 的装配理由
     * 完全一致——单独用 {@code agenttrail.ppt.model} 而不是复用 {@code agenttrail.deepresearch.model}，
     * 是为了让 PPT 流水线的模型选择只受 PPT 自己的配置项控制，不因为改 DeepResearch 的默认模型
     * 而意外联动到 PPT 的 SEARCH 状态。
     */
    @Bean
    public SearchStrategy pptSearchStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new SearchStrategy(executorFactory.forInternalOrchestration(modelId, true));
    }

    @Bean
    public TemplateStrategy pptTemplateStrategy(
            @Value("${agenttrail.ppt.template-path:src/main/resources/ppt-templates/default-template.pptx}")
            String templatePath,
            @Value("${agenttrail.ppt.template-allowlist-dir:src/main/resources/ppt-templates}") String allowlistDir,
            PptTemplateRegistry pptTemplateRegistry) {
        return new TemplateStrategy(templatePath, allowlistDir, pptTemplateRegistry);
    }

    /**
     * 启动时注册默认模板的不可变版本。模板文件缺失或 checksum 无法计算会让应用启动失败，
     * 而不是等到用户请求时才发现“当前最新版”不可用。
     */
    @Bean
    public PptTemplateRegistry pptTemplateRegistry(
            @Value("${agenttrail.ppt.template-path:src/main/resources/ppt-templates/default-template.pptx}")
            String templatePath) {
        java.nio.file.Path path = java.nio.file.Path.of(templatePath).toAbsolutePath().normalize();
        InMemoryPptTemplateRegistry registry = new InMemoryPptTemplateRegistry();
        registry.register(PptTemplateVersion.defaultContract(path.toString(), PptTemplateChecksum.sha256(path)));
        return registry;
    }

    @Bean
    public OutlineStrategy pptOutlineStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new OutlineStrategy(executorFactory.forInternalOrchestration(modelId, false));
    }

    @Bean
    public SchemaStrategy pptSchemaStrategy(AgentLoopExecutorFactory executorFactory,
            @Value("${agenttrail.ppt.model:deepseek-chat}") String modelId) {
        return new SchemaStrategy(executorFactory.forInternalOrchestration(modelId, false));
    }

    @Bean
    public RenderStrategy pptRenderStrategy(PptPythonRenderer pptPythonRenderer,
            @Qualifier("pptRenderExecutor") ExecutorService pptRenderExecutor,
            @Value("${agenttrail.ppt.output-dir:target/ppt-output}") String outputDir) {
        return new RenderStrategy(new com.agenttrail.capability.ppt.ProcessBuilderRenderPort(pptPythonRenderer),
                outputDir, pptRenderExecutor);
    }

    /**
     * 产物先通过统一端口保存。默认开发实现把对象放入 target/ppt-artifacts 并用稳定 key 复用；
     * 生产部署可将该 Bean 替换为 MinIO/S3 实现，任务上下文无需改动。
     */
    @Bean
    public PptArtifactStore pptArtifactStore(
            @Value("${agenttrail.ppt.artifact-root:target/ppt-artifacts}") String artifactRoot) {
        return new LocalPptArtifactStore(java.nio.file.Path.of(artifactRoot));
    }

    @Bean
    public VerifyStrategy pptVerifyStrategy(PptArtifactStore pptArtifactStore) {
        return new VerifyStrategy(pptArtifactStore);
    }

    /**
     * 文生图 API 客户端（issue #31）——DashScope {@code qwen-image-plus}，直接 REST 调用（接口契约
     * 见 {@link DashScopeImageClient} 类注释），复用同一个 DashScope API Key（issue #20
     * 多模型路由/issue #27 图片多模态问答已经在用的同一个 DashScope 账号，不是新申请的 key）。
     */
    @Bean
    public TextToImageClient pptTextToImageClient(
            @Value("${agenttrail.ppt.image.endpoint}") String endpoint,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${agenttrail.ppt.image.model}") String model,
            @Value("${agenttrail.ppt.image.size}") String size,
            @Value("${agenttrail.ppt.image.timeout-seconds}") long timeoutSeconds) {
        return new DashScopeImageClient(endpoint, apiKey, model, size, Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * 配图立即转存 MinIO（issue #31）——独立的 {@code agenttrail-ppt-images} bucket，和 issue #23
     * 图表的 {@code agenttrail-charts} bucket 同一个"共享 MinIO 实例、各功能各自一份 bucket"约定，
     * 复用同一套 {@code agenttrail.minio.*} 连接配置。
     */
    @Bean
    public MinioClient minioClient(
            @Value("${agenttrail.minio.endpoint}") String endpoint,
            @Value("${agenttrail.minio.access-key}") String accessKey,
            @Value("${agenttrail.minio.secret-key}") String secretKey,
            @Value("${agenttrail.ppt.image.timeout-seconds}") long timeoutSeconds) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
                .build();
    }

    @Bean
    public PptImageStore pptImageStore(
            @Value("${agenttrail.minio.endpoint}") String endpoint,
            @Value("${agenttrail.ppt.image.minio-bucket}") String bucket,
            @Value("${agenttrail.ppt.image.timeout-seconds}") long timeoutSeconds,
            MinioClient minioClient) {
        return new MinioPptImageStore(minioClient, endpoint, bucket, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    public ImageStrategy pptImageStrategy(TextToImageClient pptTextToImageClient, PptImageStore pptImageStore) {
        return new ImageStrategy(pptTextToImageClient, pptImageStore);
    }

    /** 指标 bean 可选：没有 actuator/registry 的开发环境仍使用同一状态机，只是不注册时间序列。 */
    @Bean
    public PptGenerationMetrics pptGenerationMetrics(ObjectProvider<MeterRegistry> meterRegistries) {
        return new PptGenerationMetrics(meterRegistries.getIfAvailable());
    }

    /**
     * Spring 按接口类型把上面全部 {@link PptGenerationStrategy} bean 自动收集成一个
     * {@code List}——不需要在这里手写"哪个状态对应哪个实现"的映射，
     * {@link PptGenerationService} 构造函数自己按 {@code handledState()} 建分发表。
     */
    @Bean
    public PptGenerationService pptGenerationService(PptTaskStore pptTaskStore,
            List<PptGenerationStrategy> pptGenerationStrategies, LeaseManager pptLeaseManager,
            PptGenerationMetrics pptGenerationMetrics) {
        return new PptGenerationService(pptTaskStore, pptGenerationStrategies, pptLeaseManager,
                PptRetryPolicy.defaults(), new PptCancellationRegistry(), pptGenerationMetrics);
    }
}
