package com.agenttrail.loop.tools.websearch;

/**
 * 一条联网搜索结果，跟具体搜索供应商解耦（issue #22）——换一家供应商时，
 * 只需要新增一个 {@link WebSearchResultParser} 实现，这个结构本身不用变。
 */
public record SearchResult(String url, String title, String content) {
}
