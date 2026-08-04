package com.agenttrail.capability.analytics;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/** DataAgent 的六个延迟工具白名单；通用文件、Shell 和搜索能力不会进入这份清单。 */
public final class AnalyticsToolProvider {
    private final List<ToolCallback> deferredTools;

    public AnalyticsToolProvider(List<ToolCallback> deferredTools) {
        this.deferredTools = List.copyOf(deferredTools);
    }

    public List<ToolCallback> deferredTools() {
        return deferredTools;
    }
}
