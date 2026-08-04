package com.agenttrail.capability.analytics.schema;

import com.agenttrail.loop.tools.JsonToolCallback;
import org.springframework.ai.tool.ToolCallback;

/** 列出分析数据源中可见的业务表。 */
public final class ListTablesTool {
    private ListTablesTool() { }

    public static ToolCallback callback(SchemaProvider provider) {
        return new JsonToolCallback(
                "list_tables",
                "列出分析数据源中的表、说明和关联关系。",
                "{\"type\":\"object\",\"properties\":{}}",
                ignored -> provider.listTables());
    }
}
