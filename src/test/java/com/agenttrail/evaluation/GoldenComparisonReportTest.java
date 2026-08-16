package com.agenttrail.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenComparisonReportTest {

    private static GoldenTaskReport.GoldenObservation observation(String id, String dimension, boolean passed,
                                                                 int rounds) {
        return new GoldenTaskReport.GoldenObservation(id, dimension, passed, passed ? "" : "failed",
                rounds, 100, "", "", List.of(), Map.of(), "问题");
    }

    private static GoldenTaskReport report(GoldenTaskReport.GoldenObservation... observations) {
        return new GoldenTaskReport(List.of(observations));
    }

    @Test
    void separatesWhatTheVariantFixedFromWhatItBroke() {
        GoldenTaskReport baseline = report(
                observation("sql-001", "sql_correctness", false, 8),
                observation("perm-002", "permission", true, 6));
        GoldenTaskReport variant = report(
                observation("sql-001", "sql_correctness", true, 5),
                observation("perm-002", "permission", false, 7));

        String markdown = GoldenComparisonReport.markdown("prompts", baseline, "prompts-variants/exp-a", variant);

        assertThat(markdown).contains("变体修好 1 条，弄坏 1 条");
        assertThat(markdown).contains("**变体修好**").contains("**变体弄坏**");
        assertThat(markdown).contains("8 → 5");
    }

    /** 净值为正不等于该合入——安全门禁层弄坏一条就是否决项，报告必须把这句话摆在数字旁边。 */
    @Test
    void warnsThatANetPositiveIsNotAutomaticallyAnImprovement() {
        String markdown = GoldenComparisonReport.markdown("prompts", report(), "variant", report());

        assertThat(markdown).contains("净值为正也不等于该合入")
                .contains("permission").contains("masking").contains("否决项");
    }

    /** 两轮用例集合不同（比如变体那轮新增了 case）时不能崩，标成"仅某一边有"。 */
    @Test
    void toleratesCasesPresentInOnlyOneRun() {
        GoldenTaskReport baseline = report(observation("only-baseline", "cost", true, 3));
        GoldenTaskReport variant = report(observation("only-variant", "cost", true, 3));

        String markdown = GoldenComparisonReport.markdown("a", baseline, "b", variant);

        assertThat(markdown).contains("仅基线有").contains("仅变体有");
    }

    @Test
    void reportsPassRatesForBothRuns() {
        GoldenTaskReport baseline = report(observation("a", "cost", true, 1), observation("b", "cost", false, 1));
        GoldenTaskReport variant = report(observation("a", "cost", true, 1), observation("b", "cost", true, 1));

        String markdown = GoldenComparisonReport.markdown("base", baseline, "var", variant);

        assertThat(markdown).contains("1/2").contains("2/2");
    }
}
