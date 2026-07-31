package com.agenttrail.loop.tools.chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 图表生成工具的类型化标记（issue #23）——和 {@code websearch.WebSearchToolCallback} 同一个理由：
 * 让调用方能用 {@code instanceof ChartToolCallback} 识别"这是图表工具"，不用像参考实现那样靠
 * {@code toolName.contains("chart")} 猜字符串。mcp-echarts 一个 MCP 端点实际挂了
 * {@code generate_bar_chart}/{@code generate_line_chart}/{@code generate_echarts} 等十几个
 * 不同名字的工具（每种图表类型一个），靠字符串匹配单个固定工具名在这里完全不成立，必须靠类型标记。
 *
 * <p>需要解一层信封：mcp-echarts 返回的图片 URL 确实是纯文本、不是 base64，但它仍然是包在标准
 * MCP 工具结果的 {@code [{"text": "<url>"}]} 数组信封里的——和 Tavily 的原始响应同一个 MCP 传输层
 * 形状（见 {@code websearch.TavilyWebSearchResultParser}），不解开这层信封的话，模型和最终用户
 * 看到的就是整段 JSON 数组字符串而不是一个能直接点开的链接。解不开信封（形状不对/异常）时原样
 * 透传 delegate 的返回值，不吞异常、不静默丢数据。
 */
public final class ChartToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ChartToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback delegate;

    public ChartToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
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
        return unwrapTextEnvelope(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return unwrapTextEnvelope(delegate.call(toolInput, toolContext));
    }

    private static String unwrapTextEnvelope(String rawResult) {
        try {
            JsonNode root = MAPPER.readTree(rawResult);
            if (!root.isArray() || root.isEmpty()) {
                return rawResult;
            }
            JsonNode textNode = root.get(0).get("text");
            if (textNode == null || !textNode.isTextual()) {
                return rawResult;
            }
            return textNode.asText();
        } catch (Exception notJson) {
            log.debug("图表工具结果不是预期的 MCP 信封形状，原样透传：{}", notJson.getMessage());
            return rawResult;
        }
    }
}
