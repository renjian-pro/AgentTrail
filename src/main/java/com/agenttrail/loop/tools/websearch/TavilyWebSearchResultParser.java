package com.agenttrail.loop.tools.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Tavily MCP 工具调用结果的解析（issue #22）——外层是一个数组，{@code [0].text} 是一段 JSON
 * （有时被再包一层字符串，有时直接是对象，两种都要认），里面的 {@code results} 数组才是
 * 真正的搜索结果列表。这是 Tavily 自己的响应形状，不代表所有搜索供应商都长这样——
 * 这也是为什么这个解析逻辑要关在一个按供应商命名的实现类里，不直接摆在调用方代码里。
 */
public class TavilyWebSearchResultParser implements WebSearchResultParser {

    private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchResultParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<SearchResult> parse(String rawToolResult) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(rawToolResult);
            if (!root.isArray() || root.isEmpty()) {
                return results;
            }

            JsonNode textNode = root.get(0).get("text");
            if (textNode == null || textNode.isNull()) {
                return results;
            }
            JsonNode payload = textNode.isTextual() ? MAPPER.readTree(textNode.asText()) : textNode;

            JsonNode items = payload.get("results");
            if (items == null || !items.isArray()) {
                return results;
            }
            for (JsonNode item : items) {
                String url = textOf(item, "url");
                if (url == null || url.isBlank()) {
                    continue;
                }
                results.add(new SearchResult(url, textOf(item, "title"), textOf(item, "content")));
            }
        } catch (Exception malformed) {
            log.warn("解析 Tavily 搜索结果失败：{}", malformed.getMessage());
        }
        return results;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }
}
