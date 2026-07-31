package com.agenttrail.web;

import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.tools.chart.ChartToolProvider;
import com.agenttrail.loop.tools.websearch.TavilySearchToolProvider;
import com.agenttrail.loop.tools.websearch.TavilyWebSearchResultParser;
import com.agenttrail.loop.tools.websearch.WebSearchResultParser;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

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
 * <p>这里仍然只接了裸引擎——没暂停恢复/追踪审计/分层记忆/文件问答，见
 * {@code docs/architecture.md} 第四节的扩展模式。
 */
@Configuration
public class AgentLoopExecutorConfig {

    /** 会话单飞注册必须全局共享一份，不能每个模型各建一份，否则同一会话换模型问单飞检测会失效。 */
    @Bean
    public AgentTaskManager agentTaskManager() {
        return new AgentTaskManager();
    }

    @Bean
    public WebSearchResultParser webSearchResultParser() {
        return new TavilyWebSearchResultParser();
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
            ChartToolProvider chartToolProvider) {
        List<RegisteredModel> models = List.of(
                new RegisteredModel("deepseek-chat", deepSeekChatModel, ThinkingMode.REASONING_CONTENT),
                // qwen-plus 是非思考变体，先按 DISABLED 处理——等真实 DASHSCOPE_API_KEY 到位后要实测校正
                new RegisteredModel("qwen-plus", qwenChatModel, ThinkingMode.DISABLED));
        return new AgentLoopExecutorFactory(models, "qwen-plus", agentTaskManager, tavilySearchToolProvider,
                chartToolProvider);
    }
}
