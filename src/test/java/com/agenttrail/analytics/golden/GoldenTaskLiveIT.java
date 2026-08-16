package com.agenttrail.analytics.golden;

import com.agenttrail.capability.analytics.permission.DataScopeRewriter;
import com.agenttrail.capability.analytics.sql.ColumnMeta;
import com.agenttrail.capability.analytics.sql.ReadOnlyQueryRunner;
import com.agenttrail.capability.analytics.sql.RewrittenSqlRecorder;
import com.agenttrail.capability.analytics.sql.SqlResult;
import com.agenttrail.capability.analytics.sql.SqlSafetyGuard;
import com.agenttrail.capability.analytics.sql.ValidationResult;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.sys.datascope.DataScopeResolver;
import com.agenttrail.evaluation.GoldenCase;
import com.agenttrail.evaluation.GoldenTaskReport;
import com.agenttrail.evaluation.GoldenTaskRunner;
import com.agenttrail.web.service.AgentLoopExecutorFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 {@link GoldenTaskRunner} 接到真实的 Analytics 执行链上——{@link GoldenTaskRunnerTest} 只验证
 * harness 本身（loader/assertion/report 拼装），喂给它的是写死"永远成功"的假 executor，从没有
 * 真正调用过 {@code AgentLoopExecutorFactory#forAnalytics}，也就从没产出过一份真实的准确率数字。
 *
 * <p>每个 case 的执行方式：{@code forAnalytics(null)} 拿到和生产环境完全一致的执行器
 * （ToolSearch 延迟发现六个分析工具），跑一次 {@code stream()}；从事件流里拿到模型实际调用
 * {@code execute_sql} 时传的原始 SQL 参数后，独立复用 {@link SqlSafetyGuard}/{@link DataScopeResolver}/
 * {@link DataScopeRewriter} 这三个和生产 {@code ExecuteSqlTool} 完全相同的 Bean 重新推导一遍
 * "服务端实际会执行的 SQL"——不用改动 {@code ExecuteSqlTool} 的生产装配去加一个只有测试用得上的
 * sink。再用 {@link ReadOnlyQueryRunner} 把这条 SQL 和 {@code reference_sql} 各自独立跑一遍，
 * 用于 {@code result_matches_reference} 这类指标。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "agenttrail.golden.enabled", matches = "true")
class GoldenTaskLiveIT {

    @Autowired
    private AgentLoopExecutorFactory executorFactory;
    @Autowired
    private SqlSafetyGuard safetyGuard;
    @Autowired
    private DataScopeResolver scopeResolver;
    @Autowired
    private DataScopeRewriter scopeRewriter;
    @Autowired
    private ReadOnlyQueryRunner queryRunner;
    @Autowired
    private RewrittenSqlRecorder rewrittenSqlRecorder;
    @Autowired
    @Qualifier("dataSource")
    private DataSource appDataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runsAllGoldenCasesAgainstTheRealAnalyticsAgentAndReportsPassRate() {
        Map<String, Long> userIdsByUsername = loadUserIds();
        GoldenTaskReport report = GoldenTaskRunner.run(testCase -> observe(testCase, userIdsByUsername));

        String markdown = report.markdown();
        System.out.println(markdown);
        writeReport(markdown);
        writeFailureDetails(report);

        // 第一次真实跑通：如实记录结果，不为了让断言变绿而放宽评判标准——通过率数字本身
        // 就是交付物。只断言"每条 case 都真的跑完了一次真实调用"，不断言全部通过。
        assertThat(report.observations()).hasSameSizeAs(GoldenTaskRunner.loadAll());
    }

    private GoldenTaskReport.GoldenObservation observe(GoldenCase testCase, Map<String, Long> userIdsByUsername) {
        Long userId = userIdsByUsername.get(testCase.asUser());
        if (userId == null) {
            throw new IllegalStateException("Golden Task " + testCase.id() + " 引用了未知的 as_user: " + testCase.asUser());
        }

        long startedAt = System.nanoTime();
        AgentLoopExecutor executor = executorFactory.forAnalytics(null);
        RunnableParams params = new RunnableParams(
                "golden-" + testCase.id() + "-" + UUID.randomUUID(),
                String.valueOf(userId),
                Map.of("userId", String.valueOf(userId)));

        List<AgentStreamEvent> events = executor.stream(testCase.question(), params)
                .collectList()
                .block(Duration.ofMinutes(3));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<String> toolCalls = new ArrayList<>();
        StringBuilder resultText = new StringBuilder();
        String rawSql = null;
        for (AgentStreamEvent event : events == null ? List.<AgentStreamEvent>of() : events) {
            switch (event) {
                case AgentStreamEvent.ToolStart start -> {
                    toolCalls.add(start.toolName());
                    if ("execute_sql".equals(start.toolName())) {
                        rawSql = extractSqlArgument(start.arguments());
                    }
                }
                case AgentStreamEvent.ToolEnd end -> {
                    if ("execute_sql".equals(end.toolName())) {
                        resultText.append(end.result()).append('\n');
                    }
                }
                case AgentStreamEvent.Text text -> resultText.append(text.content());
                case AgentStreamEvent.Error error -> resultText.append("[error:").append(error.code())
                        .append("] ").append(error.message());
                default -> { }
            }
        }

        Map<String, Object> metrics = new HashMap<>();
        // 模型自己写的那份 SQL 单独留档：`model_sql_not_contains` 用它钉住"权限条件不是模型写的"。
        if (rawSql != null && !rawSql.isBlank()) {
            metrics.put("modelSql", rawSql);
        }
        // 优先用生产路径**真实**改写出来的那条（RewrittenSqlRecorder），拿不到才退回在测试侧
        // 重跑一遍改写。两者的区别不是文本，是断言强度：前者证明"生产确实注入了"，后者只证明
        // "改写器如果被调用会注入"——后者漏得掉"这条链路压根没走改写"这类 bug（issue #97）。
        String actualSql = rewrittenSqlRecorder.takeLast().orElse("");
        if (actualSql.isBlank() && rawSql != null && !rawSql.isBlank()) {
            actualSql = rewriteAsProductionWould(rawSql, userId);
        }
        if (!actualSql.isBlank()) {
            populateMetrics(metrics, actualSql, testCase.referenceSql());
        }

        // 粗粒度近似：手写 loop 没有对外暴露显式的"第几轮"计数器，工具调用数是一个安全的
        // 上界近似——真实轮次只会更少（同一轮内并行工具调用只算一轮），不会更多。
        int rounds = toolCalls.size() + 1;

        return new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), true, "",
                rounds, elapsedMs, actualSql, resultText.toString(), toolCalls, metrics);
    }

    /** 独立复现 {@code ExecuteSqlTool} 的 validate→rewrite 链路，不需要改动生产装配加测试专用 sink。 */
    private String rewriteAsProductionWould(String rawSql, long userId) {
        ValidationResult validated = safetyGuard.validate(rawSql);
        if (!validated.valid()) {
            return "";
        }
        return scopeRewriter.rewrite(validated.safeSql(), scopeResolver.resolve(userId));
    }

    private void populateMetrics(Map<String, Object> metrics, String actualSql, String referenceSql) {
        try {
            SqlResult actual = queryRunner.query(actualSql);
            metrics.put("rowCount", actual.rows().size());
            if (actual.rows().size() == 1) {
                List<Object> row = actual.rows().get(0);
                for (int i = 0; i < actual.columns().size() && i < row.size(); i++) {
                    ColumnMeta column = actual.columns().get(i);
                    metrics.put("scalar." + column.label(), toLong(row.get(i)));
                }
            }
            if (referenceSql != null && !referenceSql.isBlank()) {
                SqlResult reference = queryRunner.query(referenceSql);
                metrics.put("resultMatchesReference", sameRows(actual, reference));
            }
        } catch (Exception failure) {
            metrics.put("queryError", failure.getMessage());
        }
    }

    private static boolean sameRows(SqlResult actual, SqlResult reference) {
        List<List<String>> actualRows = actual.rows().stream()
                .map(row -> row.stream().map(String::valueOf).toList()).toList();
        List<List<String>> referenceRows = reference.rows().stream()
                .map(row -> row.stream().map(String::valueOf).toList()).toList();
        return actualRows.size() == referenceRows.size()
                && new java.util.HashSet<>(actualRows).equals(new java.util.HashSet<>(referenceRows));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String extractSqlArgument(String argumentsJson) {
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            JsonNode sql = node.get("sql");
            return sql == null ? null : sql.asText();
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    private Map<String, Long> loadUserIds() {
        Map<String, Long> byUsername = new LinkedHashMap<>();
        JdbcClient.create(appDataSource).sql("SELECT id, username FROM sys_user")
                .query((rs, rowNum) -> Map.entry(rs.getString("username"), rs.getLong("id")))
                .list()
                .forEach(entry -> byUsername.put(entry.getKey(), entry.getValue()));
        return byUsername;
    }

    private void writeReport(String markdown) {
        try {
            Files.writeString(Path.of("docs/golden-task-report-2026-08-05.md"), markdown);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** 失败 case 的完整上下文（实际 SQL、原始输出、工具调用序列）——诊断真实产品问题必须要有这些，
     * 只看 markdown 汇总表里的一句 reason 猜不出根因。 */
    private void writeFailureDetails(GoldenTaskReport report) {
        StringBuilder detail = new StringBuilder("# Golden Task 失败详情\n\n");
        for (GoldenTaskReport.GoldenObservation observation : report.observations()) {
            if (observation.passed()) continue;
            detail.append("## ").append(observation.id()).append(" (").append(observation.dimension()).append(")\n\n")
                    .append("- reason: ").append(observation.reason()).append('\n')
                    .append("- toolCalls: ").append(observation.toolCalls()).append('\n')
                    .append("- actualSql: `").append(observation.actualSql()).append("`\n")
                    .append("- metrics: ").append(observation.metrics()).append('\n')
                    .append("- actualResult:\n\n```\n").append(observation.actualResult()).append("\n```\n\n");
        }
        try {
            Files.writeString(Path.of("docs/golden-task-report-2026-08-05-failures.md"), detail.toString());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
