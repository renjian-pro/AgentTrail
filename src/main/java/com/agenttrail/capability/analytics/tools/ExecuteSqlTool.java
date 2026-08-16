package com.agenttrail.capability.analytics.tools;

import com.agenttrail.capability.analytics.config.AnalyticsExecutionProperties;
import com.agenttrail.capability.analytics.mask.SensitiveFilter;
import com.agenttrail.capability.analytics.permission.DataScopeRewriter;
import com.agenttrail.capability.analytics.sql.ExplainPrecheckService;
import com.agenttrail.capability.analytics.sql.ReadOnlyQueryRunner;
import com.agenttrail.capability.analytics.sql.SqlErrorClassifier;
import com.agenttrail.capability.analytics.sql.SqlResult;
import com.agenttrail.capability.analytics.sql.SqlResultFormatter;
import com.agenttrail.capability.analytics.sql.SqlSafetyGuard;
import com.agenttrail.capability.analytics.sql.ValidationResult;
import com.agenttrail.loop.tools.JsonToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.agenttrail.loop.tools.ToolArguments;
import com.agenttrail.sys.datascope.DataScopeContext;
import com.agenttrail.sys.datascope.DataScopeResolver;
import org.springframework.ai.tool.ToolCallback;

import java.util.function.Consumer;

/** validate -> rewrite -> EXPLAIN -> 只读执行 -> 脱敏 -> Markdown。 */
public final class ExecuteSqlTool {

    private static final Logger log = LoggerFactory.getLogger(ExecuteSqlTool.class);
    private final SqlSafetyGuard safetyGuard;
    private final DataScopeResolver scopeResolver;
    private final DataScopeRewriter scopeRewriter;
    private final ExplainPrecheckService explain;
    private final ReadOnlyQueryRunner runner;
    private final SensitiveFilter sensitiveFilter;
    private final AnalyticsExecutionProperties properties;
    private final Consumer<String> rewrittenSqlSink;

    public ExecuteSqlTool(SqlSafetyGuard safetyGuard,
                          DataScopeResolver scopeResolver,
                          DataScopeRewriter scopeRewriter,
                          ExplainPrecheckService explain,
                          ReadOnlyQueryRunner runner,
                          SensitiveFilter sensitiveFilter,
                          AnalyticsExecutionProperties properties) {
        this(safetyGuard, scopeResolver, scopeRewriter, explain, runner, sensitiveFilter, properties, null);
    }

    public ExecuteSqlTool(SqlSafetyGuard safetyGuard,
                          DataScopeResolver scopeResolver,
                          DataScopeRewriter scopeRewriter,
                          ExplainPrecheckService explain,
                          ReadOnlyQueryRunner runner,
                          SensitiveFilter sensitiveFilter,
                          AnalyticsExecutionProperties properties,
                          Consumer<String> rewrittenSqlSink) {
        this.safetyGuard = safetyGuard;
        this.scopeResolver = scopeResolver;
        this.scopeRewriter = scopeRewriter;
        this.explain = explain;
        this.runner = runner;
        this.sensitiveFilter = sensitiveFilter;
        this.properties = properties;
        this.rewrittenSqlSink = rewrittenSqlSink;
    }

    public ToolCallback callback() {
        return new JsonToolCallback(
                "execute_sql",
                "执行只读分析 SQL。服务端自动校验、注入当前用户数据权限、EXPLAIN 预检并对结果脱敏。",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"},\"userId\":{\"type\":\"string\",\"description\":\"服务端自动注入的当前用户 ID\"}},\"required\":[\"sql\"]}",
                this::execute);
    }

    private String execute(ToolArguments arguments) {
        String sql = arguments.text("sql");
        if (sql == null || sql.isBlank()) {
            return "Error: sql 不能为空";
        }
        Long userId = parseUserId(arguments.text("userId"));
        if (userId == null) {
            return "Error: 无法确定当前用户身份，查询被拒绝";
        }
        ValidationResult validated = safetyGuard.validate(sql);
        if (!validated.valid()) {
            return "Error: SQL 安全校验未通过：" + validated.reason();
        }
        try {
            DataScopeContext context = scopeResolver.resolve(userId);
            String rewritten = scopeRewriter.rewrite(validated.safeSql(), context);
            if (rewrittenSqlSink != null) {
                rewrittenSqlSink.accept(rewritten);
            }
            ExplainPrecheckService.PrecheckResult precheck = explain.precheck(rewritten);
            if (!precheck.allowed()) {
                return "Error: 执行计划预检未通过：" + precheck.reason();
            }
            SqlResult result = runWithTransientRetry(rewritten);
            SqlResult masked = sensitiveFilter.mask(result, rewritten);
            return SqlResultFormatter.format(masked, properties.getPreviewRows());
        } catch (Exception failure) {
            // 给模型的是分类过的简短原因（它要据此决定下一步），给日志的是完整堆栈——
            // 2026-08-16 的评测里出现过一条裸的 NullPointerException，只看模型侧的文案
            // 完全无从定位（和 GoldenTaskRunner 那个 "executor failed: null" 是同一类问题）。
            log.warn("execute_sql 执行失败，sql={}", sql, failure);
            return "Error: 查询执行失败：" + SqlErrorClassifier.message(failure);
        }
    }

    private SqlResult runWithTransientRetry(String sql) throws Exception {
        int attempts = Math.max(0, properties.getTransientRetries());
        Exception last = null;
        for (int attempt = 0; attempt <= attempts; attempt++) {
            try {
                return runner.query(sql);
            } catch (Exception failure) {
                last = failure;
                if (!SqlErrorClassifier.isTransient(failure) || attempt == attempts) {
                    throw failure;
                }
                try {
                    Thread.sleep(40L * (1L << Math.min(attempt, 4)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last == null ? new IllegalStateException("查询未执行") : last;
    }

    private static Long parseUserId(String value) {
        if (value == null || value.isBlank() || "default".equalsIgnoreCase(value)
                || "anonymous".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
