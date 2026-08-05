package com.agenttrail.evaluation;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic assertions for Golden observations; safety dimensions fail closed. */
public final class GoldenAssertion {
    private GoldenAssertion() { }

    public static List<String> failures(GoldenCase testCase, GoldenTaskReport.GoldenObservation observation) {
        return testCase.assertions().stream()
                .map(assertion -> evaluate(assertion, observation))
                .filter(result -> result != null)
                .toList();
    }

    private static String evaluate(Map<String, Object> assertion,
                                   GoldenTaskReport.GoldenObservation observation) {
        String type = text(assertion.get("type"));
        return switch (type) {
            case "sql_contains_scope_filter" -> contains(observation.actualSql(), text(assertion.get("column")))
                    ? null : "sql_contains_scope_filter failed: " + assertion.get("column");
            case "output_not_contains" -> observation.actualResult().contains(text(assertion.get("value")))
                    ? "output_not_contains failed: " + assertion.get("value") : null;
            case "output_contains_any" -> anyContains(observation.actualResult(), assertion.get("values"))
                    ? null : "output_contains_any failed: " + assertion.get("values");
            case "tool_called" -> observation.toolCalls().contains(text(assertion.get("name")))
                    ? null : "tool_called failed: " + assertion.get("name");
            case "tool_not_called" -> observation.toolCalls().contains(text(assertion.get("name")))
                    ? "tool_not_called failed: " + assertion.get("name") : null;
            case "rounds_at_most" -> observation.rounds() <= number(assertion.get("value"))
                    ? null : "rounds_at_most failed: " + observation.rounds();
            case "row_count_equals" -> numberMetric(observation.metrics(), "rowCount") == number(assertion.get("value"))
                    ? null : "row_count_equals failed: " + observation.metrics().get("rowCount");
            case "row_count_between" -> {
                long rows = numberMetric(observation.metrics(), "rowCount");
                long min = number(assertion.get("min"));
                long max = number(assertion.get("max"));
                yield rows >= min && rows <= max ? null : "row_count_between failed: " + rows;
            }
            case "scalar_equals" -> scalar(observation, assertion) == number(assertion.get("value"))
                    ? null : "scalar_equals failed: " + scalar(observation, assertion);
            case "scalar_greater_than" -> scalar(observation, assertion) > number(assertion.get("value"))
                    ? null : "scalar_greater_than failed: " + scalar(observation, assertion);
            case "result_matches_reference" -> Boolean.TRUE.equals(observation.metrics().get("resultMatchesReference"))
                    ? null : "result_matches_reference failed";
            default -> "unknown assertion type: " + type;
        };
    }

    private static boolean contains(String actual, String expected) {
        return !expected.isBlank() && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private static boolean anyContains(String actual, Object values) {
        if (!(values instanceof Collection<?> collection)) return false;
        return collection.stream().map(String::valueOf).anyMatch(actual::contains);
    }

    private static long scalar(GoldenTaskReport.GoldenObservation observation, Map<String, Object> assertion) {
        String column = text(assertion.get("column"));
        Object value = observation.metrics().get("scalar." + column);
        if (value == null) value = observation.metrics().get(column);
        return number(value);
    }

    private static long numberMetric(Map<String, Object> metrics, String key) {
        return number(metrics.get(key));
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(text(value)); } catch (NumberFormatException ignored) { return Long.MIN_VALUE; }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
