package com.agenttrail.capability.analytics.schema;

import com.agenttrail.loop.tools.JsonToolCallback;
import com.agenttrail.loop.tools.ToolArguments;
import org.springframework.ai.tool.ToolCallback;

/** 查看指定表的字段、类型、主键、样例和外键。 */
public final class DescribeTablesTool {
    private DescribeTablesTool() { }

    public static ToolCallback callback(SchemaProvider provider) {
        return new JsonToolCallback(
                "describe_tables",
                "查看一个或多个分析表的字段、类型、主键、样例与外键。",
                "{\"type\":\"object\",\"properties\":{\"tables\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"tables\"]}",
                arguments -> describe(provider, arguments));
    }

    private static String describe(SchemaProvider provider, ToolArguments arguments) {
        return provider.describeTables(arguments.textList("tables"));
    }
}
