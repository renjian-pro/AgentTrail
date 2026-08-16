package com.agenttrail.analytics.golden;

import com.agenttrail.evaluation.GoldenCase;
import com.agenttrail.evaluation.GoldenTaskReport;
import com.agenttrail.evaluation.GoldenTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenTaskRunnerTest {

    @Test
    void loadAllMergesDbManagedCasesAlongsideTheYamlBaseline() {
        GoldenCase extra = new GoldenCase("zz-extra", "sql_correctness", "count rentals", "admin", null, null,
                List.of(Map.of("type", "tool_called", "name", "execute_sql")));

        List<GoldenCase> cases = GoldenTaskRunner.loadAll(List.of(extra));

        assertThat(cases).extracting(GoldenCase::id).contains("zz-extra", "sql-001");
    }

    @Test
    void loadsAllFixturesAndAppliesDeterministicAssertions() {
        var report = GoldenTaskRunner.run(testCase -> {
            // 这个用例验的是 harness 本身（加载 + 断言求值），不是模型质量——所以假执行器要
            // "什么都答对"。fixture 改成中文之后（issue #97）这里的桩答案也得跟着中文，
            // 否则测出来的是"桩和 fixture 对不上"，和 harness 有没有 bug 无关。
            String result = switch (testCase.dimension()) {
                case "empty_result" -> "查询成功，没有匹配的数据";
                case "masking" -> "已脱敏：********";
                case "sql_safety" -> "该语句被拒绝：只允许 SELECT/WITH 只读查询";
                default -> testCase.id().equals("sql-005")
                        ? "结果已截断，只展示前 20 行，建议改用聚合或分页"
                        : "查询成功，返回 1 行";
            };
            List<String> tools = testCase.expectedToolCalls().isEmpty()
                    ? List.of("execute_sql", "lookup_glossary", "calculate", "Skill")
                    : testCase.expectedToolCalls();
            // 危险 SQL 用例断言 tool_not_called: calculate——被拒的查询后面不该还有计算步骤
            List<String> effectiveTools = "sql_safety".equals(testCase.dimension())
                    ? List.of("validate_sql") : tools;
            return new GoldenTaskReport.GoldenObservation(testCase.id(), testCase.dimension(), true, "", 1, 4,
                    // 同时含 dept_id 和 user_id：DEPT 档位的用例断言前者，SELF 档位（sales_a*）断言后者
                    "SELECT * FROM rental WHERE dept_id IN (3) AND user_id = 6", result, effectiveTools,
                    Map.of("rowCount", 1, "scalar.total", 1, "resultMatchesReference", true,
                            "modelSql", "SELECT COUNT(*) FROM rental"));
        });

        assertThat(report.observations()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(report.observations()).allMatch(GoldenTaskReport.GoldenObservation::passed);
    }
}
