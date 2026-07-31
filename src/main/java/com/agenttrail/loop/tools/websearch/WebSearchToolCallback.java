package com.agenttrail.loop.tools.websearch;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

/**
 * 联网搜索工具的类型化标记（issue #22）——包一层委托，让调用方能用
 * {@code instanceof WebSearchToolCallback} 识别"这是搜索工具"，不用像参考实现那样靠
 * {@code toolName.contains("tavily")}/{@code contains("search")} 猜字符串。换一个搜索
 * 供应商、工具名跟着变时，识别逻辑完全不用动。
 */
public final class WebSearchToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final WebSearchResultParser resultParser;

    public WebSearchToolCallback(ToolCallback delegate, WebSearchResultParser resultParser) {
        this.delegate = delegate;
        this.resultParser = resultParser;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }

    /** 把这次调用的原始结果解析成结构化对象——供上层（如 DeepResearch 的引用链接展示）直接用。 */
    public List<SearchResult> parseResult(String rawToolResult) {
        return resultParser.parse(rawToolResult);
    }
}
