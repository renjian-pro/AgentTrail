package com.agenttrail.capability.analytics.sql;

import com.agenttrail.loop.tools.JsonToolCallback;
import com.agenttrail.loop.tools.ToolArguments;
import org.springframework.ai.tool.ToolCallback;

public final class ValidateSqlTool {
    private ValidateSqlTool() { }

    public static ToolCallback callback(SqlSafetyGuard guard) {
        return new JsonToolCallback(
                "validate_sql",
                "在执行前解析并校验只读 SQL，不访问数据库；返回具体拒绝原因或安全 SQL。",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}",
                arguments -> validate(guard, arguments));
    }

    private static String validate(SqlSafetyGuard guard, ToolArguments arguments) {
        String sql = arguments.text("sql");
        if (sql == null || sql.isBlank()) {
            return "Error: sql 不能为空";
        }
        ValidationResult result = guard.validate(sql);
        return result.valid()
                ? "校验通过，实际执行 SQL：\n" + result.safeSql()
                : "校验未通过：" + result.reason();
    }
}
