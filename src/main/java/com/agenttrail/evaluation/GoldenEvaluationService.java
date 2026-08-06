package com.agenttrail.evaluation;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.web.AgentLoopExecutorFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final Map<String, EvaluationTask> tasks = new ConcurrentHashMap<>();

    public GoldenEvaluationService(AgentLoopExecutorFactory executorFactory,
            @Qualifier("deepResearchExecutor") Executor evaluationExecutor,
            @Qualifier("goldenEvaluationCoordinatorExecutor") Executor coordinatorExecutor,
            GoldenCaseService caseService) {
        this.executorFactory = executorFactory;
        this.evaluationExecutor = evaluationExecutor;
        this.coordinatorExecutor = coordinatorExecutor;
        this.caseService = caseService;
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
        for (AgentStreamEvent event : events == null ? List.<AgentStreamEvent>of() : events) {
            switch (event) {
                case AgentStreamEvent.Text text -> result.append(text.content());
                case AgentStreamEvent.ToolStart start -> toolCalls.add(start.toolName());
                case AgentStreamEvent.ToolEnd end -> result.append(end.result()).append('\n');
                case AgentStreamEvent.Error error -> {
                    failed = true;
                    failureReason = error.code() + ": " + error.message();
                    result.append("[error]").append(error.message());
                }
                default -> { }
            }
        }
        return new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), !failed,
                failureReason, toolCalls.size() + 1, elapsedMillis(startedAt), "", result.toString(),
                toolCalls, Map.of(), testCase.question());
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
