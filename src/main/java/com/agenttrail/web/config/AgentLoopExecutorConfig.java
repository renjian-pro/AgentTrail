package com.agenttrail.web.config;

import java.util.Map;
import com.agenttrail.web.service.RegisteredModel;
import com.agenttrail.web.service.AgentLoopExecutorFactory;
import com.agenttrail.web.service.ChatToolScopeRuntimeAdapter;
import com.agenttrail.web.service.ConversationHistoryService;
import com.agenttrail.web.controller.AgentLoopController;
import com.agenttrail.web.service.CapabilityConversationService;

import com.agenttrail.capability.analytics.AnalyticsToolProvider;
import com.agenttrail.capability.file.FileStore;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.platform.model.AgentModelProperties;
import com.agenttrail.loop.hook.SessionBudgetTracker;
import com.agenttrail.platform.tools.ToolRiskLevel;
import com.agenttrail.loop.hook.ToolRiskRegistry;
import com.agenttrail.loop.memory.JdbcMemoryStore;
import com.agenttrail.loop.memory.MemoryStore;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.pause.JdbcPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.PauseStateStore;
import com.agenttrail.loop.persistence.JdbcSessionStore;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.security.HttpRateLimiter;
import com.agenttrail.loop.security.PiiMasker;
import com.agenttrail.loop.security.PromptInjectionGuard;
import com.agenttrail.loop.security.ToolRateLimiter;
import com.agenttrail.loop.skills.SkillManager;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.task.RedisInterruptBroadcaster;
import com.agenttrail.loop.task.RedisTaskLock;
import com.agenttrail.loop.trace.JdbcTraceStore;
import com.agenttrail.loop.trace.TraceStore;
import com.agenttrail.evaluation.GoldenCaseCandidateExtractor;
import com.agenttrail.evaluation.GoldenCaseRepository;
import com.agenttrail.evaluation.GoldenCaseService;
import io.micrometer.core.instrument.MeterRegistry;
import com.agenttrail.loop.tools.FileContentTool;
import com.agenttrail.loop.tools.FileSystemTools;
import com.agenttrail.loop.tools.ViewImageTool;
import com.agenttrail.loop.tools.idempotency.IdempotencyStore;
import com.agenttrail.loop.tools.idempotency.JdbcIdempotencyStore;
import com.agenttrail.loop.tools.chart.ChartToolProvider;
import com.agenttrail.loop.tools.websearch.TavilySearchToolProvider;
import com.agenttrail.loop.tools.websearch.TavilyWebSearchResultParser;
import com.agenttrail.loop.tools.websearch.WebSearchResultParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import javax.sql.DataSource;

/**
 * V1 引擎（{@code loop.core.AgentLoopExecutor}）的生产装配——issue #20 起不再是单一模型的单例，
 * 而是按模型标识创建执行器（见 {@link AgentLoopExecutorFactory}）。文本模型统一由
 * {@link AgentModelProperties} 提供标识，{@code spring-ai-starter-model-openai} 根据
 * {@code spring.ai.openai.*} 自动装配走 DashScope 兼容端点的 {@code openAiChatModel}。
 *
 * <p>issue #22 起额外挂了一个可选的联网搜索工具（Tavily，条件挂载）；issue #23 起再挂一个
 * 图表生成工具（mcp-echarts，streamable-HTTP，见 {@link AgentLoopExecutorFactory#forModelWithCharts}）——
 * 和联网搜索不同，图表生成没有按对话开关的必要，{@code AgentLoopController} 统一挂载。
 *
 * <p>当前装配已接入会话持久化、联网搜索和图表工具；暂停恢复、追踪审计、分层记忆和文件问答
 * 仍按场景作为可选机制扩展，见 {@code docs/architecture.md} 第四节。
 */
@Configuration
@EnableConfigurationProperties(AgentModelProperties.class)
public class AgentLoopExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopExecutorConfig.class);

    /**
     * 当前工程使用的是精简 MVC starter，不依赖 JSON starter 的隐式自动装配。
     * 统一历史要把能力结果写为时间线 JSON，因此这里显式提供唯一的序列化器，避免应用在
     * CapabilityConversationService 创建前就因缺 Bean 失败。
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * 会话单飞注册必须全局共享一份，不能每个模型各建一份，否则同一会话换模型问单飞检测会失效。
     *
     * <p>安全审计 2026-08-02 的 P0：这里以前不管有没有配 Redis 都只 {@code new AgentTaskManager()}，
     * 多实例部署时同一会话可能被两个实例同时跑。{@link RedisConfig} 只在 {@code
     * agenttrail.redis.enabled=true} 时才提供 {@link RedissonClient} Bean（本机开发环境没有常驻
     * Redis，默认关闭），{@code ObjectProvider.getIfAvailable()} 拿不到时优雅回退成和以前完全一致
     * 的纯内存单实例行为；配了之后才真正启用跨实例锁 + Pub/Sub 广播，并开启锁的后台自动续期
     * （5 分钟 TTL，每 ~100 秒续一次）——不开自动续期的话，跑得比 TTL 还久的会话锁会被其它实例抢走。
     */
    @Bean
    public AgentTaskManager agentTaskManager(ObjectProvider<RedissonClient> redissonProvider) {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            return new AgentTaskManager();
        }
        RedisTaskLock lock = new RedisTaskLock(redisson, "chat-" + UUID.randomUUID(), Duration.ofMinutes(5));
        lock.startAutoRenewal();
        return new AgentTaskManager(lock, new RedisInterruptBroadcaster(redisson));
    }

    @Bean
    public WebSearchResultParser webSearchResultParser() {
        return new TavilyWebSearchResultParser();
    }

    /** V1 对话的短期历史和单轮落库共用同一实现，避免读写两套会话语义发生漂移。 */
    @Bean
    public TurnPersistenceHook turnPersistenceHook(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcSessionStore(dataSource);
    }

    @Bean
    public ConversationHistoryService conversationHistoryService(@Qualifier("dataSource") DataSource dataSource) {
        return new ConversationHistoryService(dataSource);
    }

    /**
     * 跨能力上下文摘要（issue #103）。PPT / DeepResearch 两个控制器构造注入它——
     * 漏了这个 Bean 时单测照样全绿（它们不加载完整上下文），只有起 Spring 的 IT 会炸，
     * 而那正是生产启动会发生的事。
     */
    @Bean
    public ConversationDigestService conversationDigestService(ConversationHistoryService historyService) {
        return new ConversationDigestService(historyService);
    }

    @Bean
    public PauseStateStore pauseStateStore(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcPauseStateStore(dataSource);
    }

    @Bean
    public IdempotencyStore idempotencyStore(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcIdempotencyStore(dataSource);
    }

    @Bean
    public ToolRiskRegistry toolRiskRegistry() {
        return ToolRiskRegistry.defaults();
    }

    @Bean
    public PauseConfig pauseConfig(ToolRiskRegistry toolRiskRegistry, PauseStateStore pauseStateStore) {
        return new PauseConfig(toolRiskRegistry.toolsWithLevel(ToolRiskLevel.HIGH_RISK), pauseStateStore);
    }

    /**
     * 和 {@link #agentTaskManager} 同样的降级方式：配了 Redis 就跨实例累加，没配就退回纯进程内，
     * 行为与历史一致。单飞注册早就跨实例了，预算却一直是进程内的——多实例部署下同一会话在每个
     * 实例上各算各的，{@code per-session-tokens} 实际被放大成实例个数倍。
     */
    @Bean
    public SessionBudgetTracker sessionBudgetTracker(
            ObjectProvider<RedissonClient> redissonProvider,
            @Value("${agenttrail.budget.per-session-tokens:200000}") long perSessionTokens) {
        return new SessionBudgetTracker(redissonProvider.getIfAvailable(), perSessionTokens);
    }

    @Bean
    public TraceStore traceStore(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTraceStore(dataSource);
    }

    /**
     * 分层记忆（issue #19）默认关闭——和 trace/pause 这些纯本地 DB 写入不同，
     * 每轮结束时的 {@code MemoryExtractor} 会用主对话模型多发一次同步 LLM 调用，是真实的成本，
     * 不能默认静默打开。{@code ObjectProvider.getIfAvailable()} 在
     * {@link #agentLoopExecutorFactory} 里拿不到这个 Bean 时，行为与没有这个机制时完全一致。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agenttrail.memory", name = "enabled")
    public MemoryStore memoryStore(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcMemoryStore(dataSource);
    }

    /** 评测页面「badcase 怎么加」的答案：把生产 trace 变成可复核候选，从不自动信任生产流量。 */
    @Bean
    public GoldenCaseCandidateExtractor goldenCaseCandidateExtractor(TraceStore traceStore) {
        return new GoldenCaseCandidateExtractor(traceStore);
    }

    @Bean
    public GoldenCaseRepository goldenCaseRepository(@Qualifier("dataSource") DataSource dataSource,
            ObjectMapper objectMapper) {
        return new GoldenCaseRepository(dataSource, objectMapper);
    }

    @Bean
    public GoldenCaseService goldenCaseService(GoldenCaseRepository goldenCaseRepository) {
        return new GoldenCaseService(goldenCaseRepository);
    }

    /**
     * 协调线程本身不占 {@code deepResearchExecutor} 的名额（全程 join 等所有 case 跑完，占进去会
     * 偷走一个并发名额），理由和 {@link DeepResearchConfig#deepResearchExecutor} 一致——单独命名池，
     * 不用手写 {@code new Thread(...)}：没有 Spring 生命周期管理，应用关闭时不会被优雅回收。
     */
    @Bean(name = "goldenEvaluationCoordinatorExecutor", destroyMethod = "shutdown")
    public ExecutorService goldenEvaluationCoordinatorExecutor() {
        ThreadFactory namedDaemonThread = runnable -> {
            Thread thread = new Thread(runnable, "golden-evaluation-coordinator");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(namedDaemonThread);
    }

    /**
     * 用统一的非思考文本模型做分类，不占用额外供应商配额，也不需要为它单独接一个第三方分类模型——
     * 分类任务本身足够简单，复用现有已注册的模型就够了（ticket 09）。
     */
    @Bean
    public PromptInjectionGuard promptInjectionGuard(@Qualifier("openAiChatModel") ChatModel qwenChatModel) {
        return new PromptInjectionGuard(qwenChatModel);
    }

    @Bean
    public PiiMasker piiMasker() {
        return PiiMasker.create();
    }

    /**
     * 复用 {@link RedisConfig} 已经验证过的可选装配方式：没配 Redis 时 {@code
     * ObjectProvider.getIfAvailable()} 拿到 null，{@link ToolRateLimiter} 内部据此永远放行，
     * 不会因为这一台开发机没有常驻 Redis 就报错甚至拖累应用启动。
     */
    @Bean
    public ToolRateLimiter toolRateLimiter(ObjectProvider<RedissonClient> redissonProvider,
            @Value("${agenttrail.tool-rate-limit.max-calls:30}") int maxCallsPerWindow,
            @Value("${agenttrail.tool-rate-limit.window-seconds:60}") long windowSeconds) {
        return new ToolRateLimiter(redissonProvider.getIfAvailable(), maxCallsPerWindow,
                Duration.ofSeconds(windowSeconds));
    }

    /** 同一套可选装配方式，管的是 HTTP 请求级限速而不是工具调用级，见 {@link HttpRateLimiter} 上的说明。 */
    @Bean
    public HttpRateLimiter httpRateLimiter(ObjectProvider<RedissonClient> redissonProvider,
            @Value("${agenttrail.http-rate-limit.max-calls:60}") int maxCallsPerWindow,
            @Value("${agenttrail.http-rate-limit.window-seconds:60}") long windowSeconds) {
        return new HttpRateLimiter(redissonProvider.getIfAvailable(), maxCallsPerWindow,
                Duration.ofSeconds(windowSeconds));
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(HttpRateLimiter httpRateLimiter) {
        return new RateLimitInterceptor(httpRateLimiter);
    }

    /** 对话、DeepResearch、PPT 共用 agent_session，不维护互相漂移的多套历史。 */
    @Bean
    public CapabilityConversationService capabilityConversationService(
            TurnPersistenceHook turnPersistenceHook, ObjectMapper objectMapper) {
        return new CapabilityConversationService(turnPersistenceHook, objectMapper);
    }

    /**
     * {@link ChatToolScopeRuntimeAdapter} 而不是固定装配好的 {@code LegacyAgentLoopExecutorAdapter}——
     * 后者每个模型只在这里建一次，工具列表从此钉死，联网搜索开关和图表工具都没法按请求生效。
     * 前者每次 {@code start()} 都按本次请求的 {@code webSearchEnabled} 现查
     * {@link AgentLoopExecutorFactory#forModelWithCharts}（内部有缓存，不是真的重新建执行器），
     * 图表工具始终无条件带上，和重构前 {@code AgentLoopController} 的行为对齐。
     */
    @Bean
    public com.agenttrail.capability.chat.application.RuntimeProfileRegistry runtimeProfileRegistry(
            AgentLoopExecutorFactory executorFactory, AgentTaskManager agentTaskManager, PauseConfig pauseConfig,
            AgentModelProperties modelProperties) {
        // pauseConfig 是给 resume 用的：恢复时接口只给 RunId，变体要从暂停快照的 toolParams 里取，
        // 否则被中断的分析会话会恢复成普通聊天执行器（issue #96）。
        String modelId = modelProperties.id();
        return new com.agenttrail.capability.chat.application.RuntimeProfileRegistry(
                Map.of(modelId, new ChatToolScopeRuntimeAdapter(
                        executorFactory, modelId, agentTaskManager, pauseConfig)), modelId);
    }

    @Bean
    public com.agenttrail.conversation.application.ConversationPort conversationPort(
            ConversationHistoryService historyService, CapabilityConversationService capabilityService) {
        return new com.agenttrail.conversation.application.JdbcConversationPort(historyService, capabilityService);
    }

    @Bean
    public com.agenttrail.capability.chat.application.PausedRunPort pausedRunPort(
            PauseStateStore pauseStateStore, ToolRiskRegistry toolRiskRegistry) {
        return new com.agenttrail.infrastructure.runtime.PauseStatePausedRunAdapter(
                pauseStateStore, toolRiskRegistry);
    }

    @Bean
    public com.agenttrail.capability.chat.application.ChatApplicationService chatApplicationService(
            com.agenttrail.capability.chat.application.RuntimeProfileRegistry runtimeProfileRegistry,
            com.agenttrail.conversation.application.ConversationPort conversationPort,
            com.agenttrail.capability.chat.application.PausedRunPort pausedRunPort) {
        return new com.agenttrail.capability.chat.application.ChatApplicationService(runtimeProfileRegistry,
                conversationPort, pausedRunPort);
    }

    /**
     * 构造这个 Bean 本身不发一次网络请求——{@code toolCallbacks()} 懒加载，第一次真正
     * 有对话要用联网搜索时才建连 MCP 客户端，不能在这里同步 {@code initialize()}，
     * 否则一次 Tavily 抖动就会拖慢应用启动（issue #22 明确要避免的参考实现的问题）。
     */
    @Bean
    public TavilySearchToolProvider tavilySearchToolProvider(WebSearchResultParser webSearchResultParser,
            @Value("${tavily.api-key:}") String apiKey,
            @Value("${tavily.mcp-url:https://mcp.tavily.com/mcp/}") String mcpUrl,
            @Value("${tavily.timeout-seconds:10}") long timeoutSeconds,
            @Value("${tavily.max-attempts:2}") int maxAttempts) {
        return new TavilySearchToolProvider(mcpUrl, apiKey, Duration.ofSeconds(timeoutSeconds), maxAttempts,
                webSearchResultParser);
    }

    /**
     * 构造这个 Bean 本身不发一次网络请求，理由和 {@link #tavilySearchToolProvider} 一致——
     * {@code toolCallbacks()} 懒加载，第一次真正有对话要画图时才建连 mcp-echarts 的
     * streamable-HTTP MCP 端点。mcp-echarts 是独立部署的 Node 进程（本机开发用
     * {@code npx mcp-echarts -t streamable} 启动），不是这个 Java 进程管理的子进程——
     * 这也是选 streamable-HTTP 而不是 stdio 的原因：stdio 是"一个客户端独占一个 server 子进程"，
     * 多个并发对话同时画图会串话（issue #23 明确要避免的问题）。
     */
    @Bean
    public ChartToolProvider chartToolProvider(
            @Value("${agenttrail.mcp-echarts.url:http://localhost:3033/mcp}") String mcpUrl,
            @Value("${agenttrail.mcp-echarts.timeout-seconds:15}") long timeoutSeconds,
            @Value("${agenttrail.mcp-echarts.max-attempts:2}") int maxAttempts) {
        return new ChartToolProvider(mcpUrl, Duration.ofSeconds(timeoutSeconds), maxAttempts);
    }

    /**
     * 普通聊天只拿到专用工作区里的文件能力。工作区先真实创建再交给路径白名单，既让
     * write_file/edit_file 有可审批的生产入口，也不把应用目录或用户主目录暴露给模型。
     */
    @Bean
    public FileSystemTools chatFileSystemTools(
            @Value("${agenttrail.filesystem.workspace:}") String configuredWorkspace) throws IOException {
        Path workspace = configuredWorkspace == null || configuredWorkspace.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "agenttrail-workspace")
                : Path.of(configuredWorkspace);
        Files.createDirectories(workspace);
        return FileSystemTools.builder().allowedDirs(workspace.toString()).build();
    }

    @Bean
    public AgentLoopExecutorFactory agentLoopExecutorFactory(
            @Qualifier("openAiChatModel") ChatModel qwenChatModel,
            AgentModelProperties modelProperties,
            AgentTaskManager agentTaskManager,
            TavilySearchToolProvider tavilySearchToolProvider,
            ChartToolProvider chartToolProvider,
            TurnPersistenceHook turnPersistenceHook,
            ObjectProvider<FileContentTool> fileContentToolProvider,
            FileSystemTools chatFileSystemTools,
            IdempotencyStore idempotencyStore,
            ObjectProvider<ViewImageTool> viewImageToolProvider,
            ObjectProvider<FileStore> fileStoreProvider,
            ObjectProvider<AnalyticsToolProvider> analyticsToolProvider,
            PauseConfig pauseConfig,
            SessionBudgetTracker sessionBudgetTracker,
            TraceStore traceStore,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            PromptInjectionGuard promptInjectionGuard,
            PiiMasker piiMasker,
            ToolRateLimiter toolRateLimiter,
            ObjectProvider<SkillManager> skillManagerProvider,
            ObjectProvider<MemoryStore> memoryStoreProvider,
            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManagerProvider) {
        String modelId = modelProperties.id();
        List<RegisteredModel> models = List.of(
                new RegisteredModel(modelId, qwenChatModel, ThinkingMode.DISABLED));
        // 具名装配，不用位置槽（issue #99）：漏传/传错顺序在这里是编译错误，
        // 而不是运行时某个机制静默失效——DataAgent 拿不到 SOP 就是位置槽时代的产物
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(models, modelId)
                .taskManager(agentTaskManager)
                .webSearch(tavilySearchToolProvider)
                .charts(chartToolProvider)
                .persistence(turnPersistenceHook)
                .fileContentTool(fileContentToolProvider.getIfAvailable())
                .fileSystemTools(chatFileSystemTools)
                .idempotency(idempotencyStore)
                .fileStore(fileStoreProvider.getIfAvailable())
                // issue #110 / R21：轮次落库和附件绑定必须同生共死。改成按 fileIds 精确绑之后，
                // 旧 sweep 那种"下一轮顺手扫走"的自愈没有了——中间失败会留下"轮次有、文件永远
                // turn_id IS NULL"且不可恢复。没有事务管理器时传 null，执行器退化成两次独立调用。
                .turnCommitter(turnCommitter(turnPersistenceHook, fileStoreProvider.getIfAvailable(),
                        transactionManagerProvider.getIfAvailable()))
                .analytics(analyticsToolProvider.getIfAvailable())
                .pause(pauseConfig)
                .budget(sessionBudgetTracker)
                .trace(traceStore)
                .metrics(meterRegistryProvider.getIfAvailable())
                .promptInjectionGuard(promptInjectionGuard)
                .piiMasker(piiMasker)
                .toolRateLimiter(toolRateLimiter)
                .skills(skillManagerProvider.getIfAvailable())
                .memory(memoryStoreProvider.getIfAvailable())
                .viewImageTool(viewImageToolProvider.getIfAvailable())
                .build();
        log.info("agentLoopExecutorFactory configured: profile=chat-default model={} models={} "
                        + "tools=[web-search,chart] pause={} memory={} trace={} metrics={}",
                modelId, models.stream().map(RegisteredModel::id).toList(), pauseConfig != null,
                memoryStoreProvider.getIfAvailable() != null, traceStore != null,
                meterRegistryProvider.getIfAvailable() != null);
        return factory;
    }

    /**
     * 只有三样都齐了才包事务（issue #110 / R21）。缺任何一样返回 null，执行器退化成两次独立调用
     * ——那是没有数据库的装配（单测、没配数据源）的正常形态，不是降级。
     *
     * <p>特别地，{@code fileStore} 为 null 时也不需要事务：没有附件要绑，就只剩轮次落库这一步，
     * 单步操作本来就是原子的。
     */
    private static com.agenttrail.loop.persistence.TurnCommitter turnCommitter(
            com.agenttrail.loop.persistence.TurnPersistenceHook persistenceHook,
            com.agenttrail.capability.file.FileStore fileStore,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        if (persistenceHook == null || fileStore == null || transactionManager == null) {
            return null;
        }
        return new com.agenttrail.loop.persistence.TransactionalTurnCommitter(
                persistenceHook, fileStore, transactionManager);
    }
}
