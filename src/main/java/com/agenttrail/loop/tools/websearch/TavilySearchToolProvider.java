package com.agenttrail.loop.tools.websearch;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

/**
 * Tavily 联网搜索工具的懒加载提供方（issue #22）。参考实现在应用启动时同步建连、没有超时和
 * 重试，一次 Tavily 抖动就能卡住整个应用启动；这里改成**第一次真正用到时才建连**
 * （构造这个 Bean 本身不发一次网络请求），并且建连本身带超时 + 有限次重试。
 *
 * <p>key 缺失或者连续重试后仍然失败时，返回空列表而不是抛异常——调用方据此决定"这次对话
 * 挂不挂这个工具"，工具列表里压根不会出现它，不是靠 prompt 让模型"知道搜索用不了"。
 * 只有初始化*成功*的结果会被缓存复用；失败不缓存，下一次调用会重新尝试
 * （给瞬时网络问题恢复的机会，不会因为启动时抖动一次就整个进程生命周期都不可用）。
 */
public class TavilySearchToolProvider {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchToolProvider.class);

    private final String mcpUrl;
    private final String apiKey;
    private final Duration timeout;
    private final int maxAttempts;
    private final WebSearchResultParser resultParser;

    private final Object initLock = new Object();
    private volatile List<ToolCallback> cachedToolCallbacks;

    public TavilySearchToolProvider(String mcpUrl, String apiKey, Duration timeout, int maxAttempts,
            WebSearchResultParser resultParser) {
        this.mcpUrl = mcpUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.resultParser = resultParser;
    }

    /** 懒加载 + 只缓存成功结果；key 未配置或初始化失败时返回空列表。 */
    public List<ToolCallback> toolCallbacks() {
        List<ToolCallback> cached = cachedToolCallbacks;
        if (cached != null) {
            return cached;
        }
        synchronized (initLock) {
            if (cachedToolCallbacks != null) {
                return cachedToolCallbacks;
            }
            List<ToolCallback> initialized = initializeWithRetry();
            if (!initialized.isEmpty()) {
                cachedToolCallbacks = initialized;
            }
            return initialized;
        }
    }

    private List<ToolCallback> initializeWithRetry() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("TAVILY_API_KEY 未配置，联网搜索工具本次对话不可用");
            return List.of();
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return buildToolCallbacks();
            } catch (Exception failure) {
                log.warn("Tavily MCP 客户端初始化失败（第 {}/{} 次）：{}", attempt, maxAttempts, failure.getMessage());
            }
        }
        log.warn("Tavily MCP 客户端连续 {} 次初始化失败，联网搜索工具本次对话不可用", maxAttempts);
        return List.of();
    }

    private List<ToolCallback> buildToolCallbacks() {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + apiKey);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(mcpUrl)
                .requestBuilder(requestBuilder)
                .connectTimeout(timeout)
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
        client.initialize();

        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(List.of(client))
                .build();
        return List.of(provider.getToolCallbacks()).stream()
                .map(callback -> (ToolCallback) new WebSearchToolCallback(callback, resultParser))
                .toList();
    }
}
