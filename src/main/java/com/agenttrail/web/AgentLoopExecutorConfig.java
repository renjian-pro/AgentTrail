package com.agenttrail.web;

import com.agenttrail.capability.analytics.AnalyticsToolProvider;
import com.agenttrail.loop.file.FileStore;
import com.agenttrail.loop.hook.SessionBudgetTracker;
import com.agenttrail.loop.hook.ToolRiskLevel;
import com.agenttrail.loop.hook.ToolRiskRegistry;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.pause.JdbcPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.PauseStateStore;
import com.agenttrail.loop.persistence.JdbcSessionStore;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.task.RedisInterruptBroadcaster;
import com.agenttrail.loop.task.RedisTaskLock;
import com.agenttrail.loop.trace.JdbcTraceStore;
import com.agenttrail.loop.trace.TraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import com.agenttrail.loop.tools.FileContentTool;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * V1 引擎（{@code loop.core.AgentLoopExecutor}）的生产装配——issue #20 起不再是单一模型的单例，
 * 而是按模型标识可选的一批执行器（见 {@link AgentLoopExecutorFactory}）。默认模型是
 * {@code qwen-plus}（{@code spring-ai-starter-model-openai} 根据 {@code spring.ai.openai.*}
 * 配置自动装配的 bean 名为 {@code openAiChatModel}，走 DashScope 的 OpenAI 兼容模式），
 * {@code deepseek-chat} 作为第二个可选模型保留（{@code spring-ai-starter-model-deepseek}
 * 装配的 bean 名为 {@code deepSeekChatModel}）。
 *
 * <p>issue #22 起额外挂了一个可选的联网搜索工具（Tavily，条件挂载）；issue #23 起再挂一个
 * 图表生成工具（mcp-echarts，streamable-HTTP，见 {@link AgentLoopExecutorFactory#forModelWithCharts}）——
 * 和联网搜索不同，图表生成没有按对话开关的必要，{@code AgentLoopController} 统一挂载。
 *
 * <p>当前装配已接入会话持久化、联网搜索和图表工具；暂停恢复、追踪审计、分层记忆和文件问答
 * 仍按场景作为可选机制扩展，见 {@code docs/architecture.md} 第四节。
 */
@Configuration
public class AgentLoopExecutorConfig {

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

    @Bean
    public PauseStateStore pauseStateStore(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcPauseStateStore(dataSource);
    }

    @Bean
    public ToolRiskRegistry toolRiskRegistry() {
        return ToolRiskRegistry.defaults();
    }

    @Bean
    public PauseConfig pauseConfig(ToolRiskRegistry toolRiskRegistry, PauseStateStore pauseStateStore) {
        return new PauseConfig(toolRiskRegistry.toolsWithLevel(ToolRiskLevel.HIGH_RISK), pauseStateStore);
    }

    @Bean
    public SessionBudgetTracker sessionBudgetTracker(
            @Value("${agenttrail.budget.per-session-tokens:200000}") long perSessionTokens) {
        return new SessionBudgetTracker(perSessionTokens);
    }

    @Bean
    public TraceStore traceStore(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTraceStore(dataSource);
    }

    /** 对话、DeepResearch、PPT 共用 agent_session，不维护互相漂移的多套历史。 */
    @Bean
    public CapabilityConversationService capabilityConversationService(
            TurnPersistenceHook turnPersistenceHook, ObjectMapper objectMapper) {
        return new CapabilityConversationService(turnPersistenceHook, objectMapper);
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

    @Bean
    public AgentLoopExecutorFactory agentLoopExecutorFactory(
            @Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
            @Qualifier("openAiChatModel") ChatModel qwenChatModel,
            AgentTaskManager agentTaskManager,
            TavilySearchToolProvider tavilySearchToolProvider,
            ChartToolProvider chartToolProvider,
            TurnPersistenceHook turnPersistenceHook,
            ObjectProvider<FileContentTool> fileContentToolProvider,
            ObjectProvider<FileStore> fileStoreProvider,
            ObjectProvider<AnalyticsToolProvider> analyticsToolProvider,
            PauseConfig pauseConfig,
            ToolRiskRegistry toolRiskRegistry,
            SessionBudgetTracker sessionBudgetTracker,
            TraceStore traceStore,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        List<RegisteredModel> models = List.of(
                new RegisteredModel("deepseek-chat", deepSeekChatModel, ThinkingMode.REASONING_CONTENT),
                // qwen-plus 是非思考变体，先按 DISABLED 处理——等真实 DashScope 配置到位后要实测校正
                new RegisteredModel("qwen-plus", qwenChatModel, ThinkingMode.DISABLED));
        return new AgentLoopExecutorFactory(models, "qwen-plus", agentTaskManager, tavilySearchToolProvider,
                chartToolProvider, turnPersistenceHook, fileContentToolProvider.getIfAvailable(), fileStoreProvider.getIfAvailable(),
                analyticsToolProvider.getIfAvailable(), pauseConfig, toolRiskRegistry, sessionBudgetTracker, traceStore,
                meterRegistryProvider.getIfAvailable());
    }
}
