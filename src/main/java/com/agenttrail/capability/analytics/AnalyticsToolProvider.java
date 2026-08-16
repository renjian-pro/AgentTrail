package com.agenttrail.capability.analytics;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * DataAgent 的六个工具白名单：{@code list_tables}、{@code describe_tables}、
 * {@code lookup_glossary}、{@code validate_sql}、{@code execute_sql}、{@code calculate}。
 * 通用文件、Shell 和搜索能力不会进入这份清单——DataAgent 绝不能挂 Bash，模型会绕开
 * SQL 安全校验、权限改写、脱敏整套工具栈直接连库（踩坑点 #29）。
 *
 * <p>这六个是<b>常驻</b>工具（issue #95）。它们曾经是延迟工具，靠 {@code search_tools}
 * 按需发现——那是为"工具多到撑爆上下文"设计的机制，六个工具用它是错配，实测表现为模型
 * 反复回答"没找到能查询数据的工具"。
 */
public final class AnalyticsToolProvider {
    private final List<ToolCallback> tools;

    public AnalyticsToolProvider(List<ToolCallback> tools) {
        this.tools = List.copyOf(tools);
    }

    public List<ToolCallback> tools() {
        return tools;
    }
}
