package com.agenttrail.loop.tools.websearch;

import java.util.List;

/**
 * 把某个搜索供应商 MCP 工具调用返回的原始 JSON 字符串解析成 {@link SearchResult} 列表
 * （issue #22）。按供应商分别实现——调用方只认这个接口，换供应商时新增一个实现即可，
 * 不用碰调用方代码。
 */
public interface WebSearchResultParser {

    /** 解析失败或没有结果时返回空列表，不抛异常——调用方不该因为解析失败而中断整轮对话。 */
    List<SearchResult> parse(String rawToolResult);
}
