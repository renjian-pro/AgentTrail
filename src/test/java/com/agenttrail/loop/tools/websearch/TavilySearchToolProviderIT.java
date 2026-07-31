package com.agenttrail.loop.tools.websearch;

import com.agenttrail.support.Secrets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Tavily key + 真实 MCP 端点跑一次真实搜索（issue #22 验收标准，不接受 mock）：
 * 工具能被拿到、能被真的调用、返回的原始结果能被解析成非空的结构化 {@link SearchResult} 列表。
 */
class TavilySearchToolProviderIT {

    @Test
    void fetchesRealSearchResultsFromTavily() {
        String apiKey = Secrets.require("TAVILY_API_KEY");
        WebSearchResultParser parser = new TavilyWebSearchResultParser();
        TavilySearchToolProvider provider = new TavilySearchToolProvider(
                "https://mcp.tavily.com/mcp/", apiKey, Duration.ofSeconds(20), 2, parser);

        List<ToolCallback> tools = provider.toolCallbacks();

        assertThat(tools).as("真实 key 应该能拿到至少一个 Tavily 工具").isNotEmpty();
        assertThat(tools).allSatisfy(tool -> assertThat(tool).isInstanceOf(WebSearchToolCallback.class));

        WebSearchToolCallback searchTool = (WebSearchToolCallback) tools.stream()
                .filter(tool -> tool.getToolDefinition().name().toLowerCase().contains("search"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到名字里带 search 的 Tavily 工具"));

        String rawResult = searchTool.call("{\"query\":\"Spring AI framework\"}");
        List<SearchResult> results = searchTool.parseResult(rawResult);

        assertThat(results).as("真实搜索结果解析出来不应该是空的").isNotEmpty();
        assertThat(results.get(0).url()).isNotBlank();
    }

    @Test
    void toolCallbacksIsCachedAfterASuccessfulInitialization() {
        String apiKey = Secrets.require("TAVILY_API_KEY");
        TavilySearchToolProvider provider = new TavilySearchToolProvider(
                "https://mcp.tavily.com/mcp/", apiKey, Duration.ofSeconds(20), 2, new TavilyWebSearchResultParser());

        List<ToolCallback> first = provider.toolCallbacks();
        List<ToolCallback> second = provider.toolCallbacks();

        assertThat(second).as("第二次调用应该命中缓存，返回同一份工具列表").isSameAs(first);
    }
}
