package com.agenttrail.evaluation;

import com.agenttrail.capability.analytics.permission.DataScopeRewriter;
import com.agenttrail.capability.analytics.sql.ColumnMeta;
import com.agenttrail.capability.analytics.sql.ReadOnlyQueryRunner;
import com.agenttrail.capability.analytics.sql.SqlResult;
import com.agenttrail.capability.analytics.sql.SqlSafetyGuard;
import com.agenttrail.capability.analytics.sql.ValidationResult;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.sys.datascope.DataScopeResolver;
import com.agenttrail.web.service.AgentLoopExecutorFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Asynchronous Golden evaluation orchestration; status is intentionally case-level progress only. */
@Service
public class GoldenEvaluationService {
    private final AgentLoopExecutorFactory executorFactory;
    private final Executor evaluationExecutor;
    private final Executor coordinatorExecutor;
    private final GoldenCaseService caseService;
    private final DataScopeResolver scopeResolver;
    private final ObjectProvider<SqlSafetyGuard> safetyGuardProvider;
    private final ObjectProvider<DataScopeRewriter> scopeRewriterProvider;
    private final ObjectProvider<ReadOnlyQueryRunner> queryRunnerProvider;
    private final DataSource appDataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, EvaluationTask> tasks = new ConcurrentHashMap<>();

    public GoldenEvaluationService(AgentLoopExecutorFactory executorFactory,
            @Qualifier("deepResearchExecutor") Executor evaluationExecutor,
            @Qualifier("goldenEvaluationCoordinatorExecutor") Executor coordinatorExecutor,
            GoldenCaseService caseService, DataScopeResolver scopeResolver,
            ObjectProvider<SqlSafetyGuard> safetyGuardProvider,
            ObjectProvider<DataScopeRewriter> scopeRewriterProvider,
            ObjectProvider<ReadOnlyQueryRunner> queryRunnerProvider,
            @Qualifier("dataSource") DataSource appDataSource) {
        this.executorFactory = executorFactory;
        this.evaluationExecutor = evaluationExecutor;
        this.coordinatorExecutor = coordinatorExecutor;
        this.caseService = caseService;
        this.scopeResolver = scopeResolver;
        this.safetyGuardProvider = safetyGuardProvider;
        this.scopeRewriterProvider = scopeRewriterProvider;
        this.queryRunnerProvider = queryRunnerProvider;
        this.appDataSource = appDataSource;
    }

    public GoldenEvaluationTaskResponse start() {
        List<GoldenCase> cases = caseService.casesForExecution();
        EvaluationTask task = new EvaluationTask(UUID.randomUUID().toString(), cases.size());
        tasks.put(task.taskId, task);
        // 协调任务本身不占 evaluationExecutor 的名额——它全程 join 等 37 个 case 跑完，
        // 真占进去会从池子里偷走一个线程，37 个 case 实际只能 3 路并发，不是预期的 4 路。
        coordinatorExecutor.execute(() -> run(task, cases));
        return task.response();
    }

    public Optional<GoldenEvaluationTaskResponse> status(String taskId) {
        EvaluationTask task = tasks.get(taskId);
        return task == null ? Optional.empty() : Optional.of(task.response());
    }

    public List<GoldenEvaluationHistoryItem> history() {
        return tasks.values().stream()
                .filter(task -> !GoldenEvaluationTaskResponse.RUNNING.equals(task.status))
                .sorted(Comparator.comparing((EvaluationTask task) -> task.startedAt).reversed())
                .limit(50)
                .map(EvaluationTask::historyItem)
                .toList();
    }

    private void run(EvaluationTask task, List<GoldenCase> cases) {
        try {
            task.report = GoldenTaskRunner.runCaseListConcurrently(cases, this::executeCase,
                    task.completedCases::set, evaluationExecutor);
            task.status = GoldenEvaluationTaskResponse.SUCCESS;
        } catch (RuntimeException failure) {
            task.error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            task.status = GoldenEvaluationTaskResponse.FAILED;
        }
    }

    private GoldenTaskReport.GoldenObservation executeCase(GoldenCase testCase) {
        long startedAt = System.nanoTime();
        var executor = executorFactory.forAnalytics(null);
        String userId = testCase.asUser().isBlank() ? "evaluation" : testCase.asUser();
        RunnableParams params = new RunnableParams("golden-evaluation-" + testCase.id() + "-" + UUID.randomUUID(),
                userId, Map.of("userId", userId));
        List<AgentStreamEvent> events = executor.stream(testCase.question(), params)
                .collectList().block(Duration.ofMinutes(10));
        List<String> toolCalls = new ArrayList<>();
        StringBuilder result = new StringBuilder();
        boolean failed = false;
        String failureReason = "";
        String rawSql = null;
        for (AgentStreamEvent event : events == null ? List.<AgentStreamEvent>of() : events) {
            switch (event) {
                case AgentStreamEvent.Text text -> result.append(text.content());
                case AgentStreamEvent.ToolStart start -> {
                    toolCalls.add(start.toolName());
                    if ("execute_sql".equals(start.toolName())) {
                        rawSql = extractSqlArgument(start.arguments());
                    }
                }
                case AgentStreamEvent.ToolEnd end -> result.append(end.result()).append('\n');
                case AgentStreamEvent.Error error -> {
                    failed = true;
                    failureReason = error.code() + ": " + error.message();
                    result.append("[error]").append(error.message());
                }
                default -> { }
            }
        }
        MetricsResult computed = computeMetrics(rawSql, userId, testCase.referenceSql());
        Map<String, Object> metrics = new HashMap<>(computed.metrics());
        if (rawSql != null && !rawSql.isBlank()) {
            metrics.put("modelSql", rawSql);
        }
        // actualSql 此前恒传 ""，于是 sql_contains_scope_filter 在生产评测路径上**永远失败**——
        // 权限维度的用例在页面上跑不可能变绿，和 IT 里跑出来的结果对不上（issue #98）。
        return new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), !failed,
                failureReason, toolCalls.size() + 1, elapsedMillis(startedAt), computed.rewrittenSql(),
                result.toString(), toolCalls, metrics, testCase.question());
    }

    /**
     * 复现 {@code ExecuteSqlTool} 的 validate→rewrite 链路后跑一次只读查询，算出 {@code rowCount}/
     * {@code scalar.*}/{@code resultMatchesReference}——这几个指标此前只有测试代码里的
     * {@code GoldenTaskLiveIT.populateMetrics} 真的算过，生产的 {@code /agent/v1/evaluation/run}
     * 路径一直传的是 {@code Map.of()}（roadmap 记录的踩坑点 #91）。
     *
     * <p>{@link SqlSafetyGuard}/{@link DataScopeRewriter}/{@link ReadOnlyQueryRunner} 只在
     * {@code agenttrail.analytics.datasource.enabled=true} 时才存在——默认关闭，这里用
     * {@link ObjectProvider} 可选注入，拿不到就跳过这几个指标，不阻塞整个评测服务的启动/执行。
     */
    private record MetricsResult(String rewrittenSql, Map<String, Object> metrics) {
        static final MetricsResult EMPTY = new MetricsResult("", Map.of());
    }

    private MetricsResult computeMetrics(String rawSql, String username, String referenceSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return MetricsResult.EMPTY;
        }
        SqlSafetyGuard safetyGuard = safetyGuardProvider.getIfAvailable();
        DataScopeRewriter scopeRewriter = scopeRewriterProvider.getIfAvailable();
        ReadOnlyQueryRunner queryRunner = queryRunnerProvider.getIfAvailable();
        if (safetyGuard == null || scopeRewriter == null || queryRunner == null) {
            return MetricsResult.EMPTY;
        }
        Long numericUserId = resolveUserId(username);
        if (numericUserId == null) {
            return MetricsResult.EMPTY;
        }
        ValidationResult validated = safetyGuard.validate(rawSql);
        if (!validated.valid()) {
            return MetricsResult.EMPTY;
        }
        String actualSql = scopeRewriter.rewrite(validated.safeSql(), scopeResolver.resolve(numericUserId));
        if (actualSql.isBlank()) {
            return MetricsResult.EMPTY;
        }
        Map<String, Object> metrics = new HashMap<>();
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
        return new MetricsResult(actualSql, metrics);
    }

    private Long resolveUserId(String username) {
        try {
            return JdbcClient.create(appDataSource)
                    .sql("SELECT id FROM sys_user WHERE username = :username")
                    .param("username", username)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
        } catch (Exception lookupFailure) {
            return null;
        }
    }

    private static boolean sameRows(SqlResult actual, SqlResult reference) {
        List<List<String>> actualRows = actual.rows().stream()
                .map(row -> row.stream().map(String::valueOf).toList()).toList();
        List<List<String>> referenceRows = reference.rows().stream()
                .map(row -> row.stream().map(String::valueOf).toList()).toList();
        return actualRows.size() == referenceRows.size()
                && new HashSet<>(actualRows).equals(new HashSet<>(referenceRows));
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

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static final class EvaluationTask {
        private final String taskId;
        private final long startedAt = System.currentTimeMillis();
        private final int totalCases;
        private final AtomicInteger completedCases = new AtomicInteger();
        private volatile String status = GoldenEvaluationTaskResponse.RUNNING;
        private volatile GoldenTaskReport report;
        private volatile String error;

        private EvaluationTask(String taskId, int totalCases) {
            this.taskId = taskId;
            this.totalCases = totalCases;
        }

        private GoldenEvaluationTaskResponse response() {
            return new GoldenEvaluationTaskResponse(taskId, status, completedCases.get(), totalCases, report, error);
        }

        private GoldenEvaluationHistoryItem historyItem() {
            return new GoldenEvaluationHistoryItem(taskId, status, startedAt, completedCases.get(), totalCases,
                    report == null ? 0 : report.passRate(), report == null ? Map.of() : report.dimensionPassRates());
        }
    }
}
