package com.agenttrail.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntConsumer;

/** Production-capable Golden Set loader and deterministic assertion harness. */
public final class GoldenTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(GoldenTaskRunner.class);
    private GoldenTaskRunner() { }

    private static volatile List<GoldenCase> cachedBaseline;

    public static List<GoldenCase> loadAll() {
        return loadAll(List.of());
    }

    /**
     * {@code extra} is where admin-managed cases (see {@code GoldenCaseService}) join the YAML baseline for
     * an actual evaluation run — the YAML loader itself stays oblivious to the database. The YAML baseline
     * is a fixed classpath resource, so it's parsed once and cached rather than re-scanned on every call —
     * this is on the request/evaluation-start path, not just at startup.
     */
    public static List<GoldenCase> loadAll(List<GoldenCase> extra) {
        List<GoldenCase> cases = new ArrayList<>(baseline());
        cases.addAll(extra);
        return cases.stream().sorted(Comparator.comparing(GoldenCase::id)).toList();
    }

    private static List<GoldenCase> baseline() {
        List<GoldenCase> loaded = cachedBaseline;
        if (loaded == null) {
            synchronized (GoldenTaskRunner.class) {
                loaded = cachedBaseline;
                if (loaded == null) {
                    loaded = loadBaseline();
                    cachedBaseline = loaded;
                }
            }
        }
        return loaded;
    }

    private static List<GoldenCase> loadBaseline() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:analytics/golden/*.yml");
            List<GoldenCase> cases = new ArrayList<>();
            for (Resource resource : resources) {
                try (InputStream input = resource.getInputStream()) {
                    Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                    if (!(root instanceof Map<?, ?> map)) continue;
                    Object rawCases = map.get("cases");
                    if (!(rawCases instanceof Collection<?> collection)) continue;
                    for (Object raw : collection) {
                        if (raw instanceof Map<?, ?> item) cases.add(toCase(item));
                    }
                }
            }
            return List.copyOf(cases);
        } catch (Exception failure) {
            throw new IllegalStateException("Golden Set load failed", failure);
        }
    }

    /** 异常类型 + message + 第一帧调用点——报告里一行放得下，又足够定位到代码。 */
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
        if (failure.getMessage() != null) {
            text.append(": ").append(failure.getMessage());
        }
        StackTraceElement[] frames = failure.getStackTrace();
        if (frames.length > 0) {
            text.append(" @ ").append(frames[0]);
        }
        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) {
            text.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                text.append(": ").append(cause.getMessage());
            }
        }
        return text.toString();
    }

    public static GoldenTaskReport run(Function<GoldenCase, GoldenTaskReport.GoldenObservation> executor) {
        return runCaseList(loadAll(), executor);
    }

    public static GoldenTaskReport runCaseList(List<GoldenCase> cases,
                                               Function<GoldenCase, GoldenTaskReport.GoldenObservation> executor) {
        List<GoldenTaskReport.GoldenObservation> results = cases.stream().map(testCase -> scoreCase(testCase, executor)).toList();
        return new GoldenTaskReport(results);
    }

    /**
     * 并发版本：case 之间互相独立（各自开一条新会话），用调用方给的线程池并发跑，用 case 完成的
     * 先后顺序更新 progress，但结果仍按 {@code cases} 的原始顺序落地，保证报告可复现。
     */
    public static GoldenTaskReport runCaseListConcurrently(List<GoldenCase> cases,
                                                           Function<GoldenCase, GoldenTaskReport.GoldenObservation> executor,
                                                           IntConsumer progress, Executor pool) {
        AtomicInteger completed = new AtomicInteger();
        // 先把全部 case 都提交出去，再统一 join——两步分开是必须的：submit+join 揉进同一个 map
        // 会退化回串行（每个 case 提交后立刻被 join 阻塞，下一个 case 根本没机会提交）。
        List<CompletableFuture<GoldenTaskReport.GoldenObservation>> pending = cases.stream()
                .map(testCase -> CompletableFuture.supplyAsync(() -> scoreCase(testCase, executor), pool)
                        .whenComplete((observation, failure) -> progress.accept(completed.incrementAndGet())))
                .toList();
        List<GoldenTaskReport.GoldenObservation> results = pending.stream().map(CompletableFuture::join).toList();
        return new GoldenTaskReport(results);
    }

    private static GoldenTaskReport.GoldenObservation scoreCase(GoldenCase testCase,
            Function<GoldenCase, GoldenTaskReport.GoldenObservation> executor) {
        GoldenTaskReport.GoldenObservation observation;
        try {
            observation = executor.apply(testCase);
        } catch (RuntimeException failure) {
            // 只记 message 时 NPE 会变成字面量 "executor failed: null"，查无可查——2026-08-05
            // 那轮 4 次跑批里它出现 3 次、每次命中不同 case，就是因为这里丢了类型和堆栈（issue #98）。
            log.warn("Golden case {} 执行抛异常", testCase.id(), failure);
            observation = new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), false,
                    "executor failed: " + describe(failure), 0, 0, "", "", List.of(), Map.of(),
                    testCase.question());
        }
        Map<String, Object> metrics = new LinkedHashMap<>(observation.metrics());
        if (!testCase.expectedToolCalls().isEmpty()) {
            metrics.putAll(ToolSelectionMetrics.calculate(testCase.expectedToolCalls(), observation.toolCalls()).asMap());
        }
        List<String> failures = GoldenAssertion.failures(testCase, observation);
        boolean passed = observation.passed() && failures.isEmpty();
        String reason = observation.reason();
        if (!failures.isEmpty()) {
            reason = (reason.isBlank() ? "" : reason + "; ") + String.join("; ", failures);
        }
        return new GoldenTaskReport.GoldenObservation(observation.id(), observation.dimension(), passed,
                reason, observation.rounds(), observation.elapsedMs(), observation.actualSql(),
                observation.actualResult(), observation.toolCalls(), metrics,
                observation.question().isBlank() ? testCase.question() : observation.question());
    }

    private static GoldenCase toCase(Map<?, ?> item) {
        return new GoldenCase(text(item.get("id")), text(item.get("dimension")),
                text(item.get("question")), text(item.get("as_user")),
                text(item.get("reference_sql")), item.get("expected"),
                maps(item.get("assertions")), texts(item.get("expected_tool_calls")));
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, val) -> copy.put(String.valueOf(key), val));
                result.add(Map.copyOf(copy));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> texts(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(String::valueOf).toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
