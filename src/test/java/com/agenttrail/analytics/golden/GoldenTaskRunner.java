package com.agenttrail.analytics.golden;

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
import java.util.function.Function;

/** Golden 数据集加载器和可注入执行器；默认测试只校验数据集，真实模型运行放在 golden profile。 */
public final class GoldenTaskRunner {
    private GoldenTaskRunner() { }

    public static List<GoldenCase> loadAll() {
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
            return cases.stream().sorted(Comparator.comparing(GoldenCase::id)).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("Golden Tasks 加载失败", failure);
        }
    }

    public static GoldenTaskReport run(Function<GoldenCase, GoldenTaskReport.GoldenObservation> executor) {
        List<GoldenTaskReport.GoldenObservation> results = loadAll().stream().map(testCase -> {
            GoldenTaskReport.GoldenObservation observation;
            try {
                observation = executor.apply(testCase);
            } catch (RuntimeException failure) {
                observation = new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), false,
                        "executor failed: " + failure.getMessage(), 0, 0, "", "", List.of(), Map.of());
            }
            List<String> failures = GoldenAssertion.failures(testCase, observation);
            boolean passed = observation.passed() && failures.isEmpty();
            String reason = observation.reason();
            if (!failures.isEmpty()) {
                reason = (reason.isBlank() ? "" : reason + "; ") + String.join("; ", failures);
            }
            return new GoldenTaskReport.GoldenObservation(observation.id(), observation.dimension(), passed,
                    reason, observation.rounds(), observation.elapsedMs(), observation.actualSql(),
                    observation.actualResult(), observation.toolCalls(), observation.metrics());
        }).toList();
        return new GoldenTaskReport(results);
    }

    private static GoldenCase toCase(Map<?, ?> item) {
        return new GoldenCase(text(item.get("id")), text(item.get("dimension")),
                text(item.get("question")), text(item.get("as_user")),
                text(item.get("reference_sql")), item.get("expected"),
                maps(item.get("assertions")));
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

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
