package com.agenttrail.evaluation;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 两轮 Golden 结果的逐条对照（issue #102）。
 *
 * <p><b>为什么是离线对照而不是线上分流</b>：这个项目的线上流量是开发者本人。按 userId/sessionId
 * 哈希分流跑再久，两组各几十条会话，任何差异都淹在噪声里——做出来也出不了统计结论。
 * 同一套用例、两版提示词各跑一遍，用例固定，差异归因才干净（requirements §6.5）。
 *
 * <p><b>怎么用</b>：两次独立运行，第二次把提示词目录指到变体目录，然后把两份报告喂给这里。
 *
 * <pre>
 * mvn verify -Pgolden
 * mvn verify -Pgolden -Dagenttrail.prompts.dir=prompts-variants/exp-a
 * </pre>
 *
 * <p>变体目录不放进 {@code prompts/} 主目录——否则 {@code PromptRegistry} 会把两版当成 id 重复
 * 直接拒绝加载，而且主目录里躺着实验稿本身就是污染。
 */
public final class GoldenComparisonReport {

    private GoldenComparisonReport() {
    }

    /** 一条用例在两轮之间的变化。 */
    public record CaseDelta(String id, String dimension, Boolean baseline, Boolean variant,
                            long baselineRounds, long variantRounds, String note) {

        /** 只有"一边过一边不过"才是需要人看的信号；两边都过或都不过说明这条用例对这次改动不敏感。 */
        public boolean changed() {
            return baseline != null && variant != null && !baseline.equals(variant);
        }

        public String verdict() {
            if (baseline == null) return "仅变体有";
            if (variant == null) return "仅基线有";
            if (baseline.equals(variant)) return baseline ? "都通过" : "都失败";
            return Boolean.TRUE.equals(variant) ? "**变体修好**" : "**变体弄坏**";
        }
    }

    public static String markdown(String baselineLabel, GoldenTaskReport baseline,
                                  String variantLabel, GoldenTaskReport variant) {
        Map<String, GoldenTaskReport.GoldenObservation> left = byId(baseline);
        Map<String, GoldenTaskReport.GoldenObservation> right = byId(variant);
        Set<String> ids = new LinkedHashSet<>(left.keySet());
        ids.addAll(right.keySet());

        StringBuilder text = new StringBuilder("# Golden A/B 对照\n\n");
        text.append("- 基线：`").append(baselineLabel).append("` — ")
                .append(passRate(baseline)).append('\n');
        text.append("- 变体：`").append(variantLabel).append("` — ")
                .append(passRate(variant)).append("\n\n");

        long fixed = ids.stream().map(id -> delta(id, left.get(id), right.get(id)))
                .filter(d -> d.changed() && Boolean.TRUE.equals(d.variant())).count();
        long broken = ids.stream().map(id -> delta(id, left.get(id), right.get(id)))
                .filter(d -> d.changed() && Boolean.FALSE.equals(d.variant())).count();
        text.append("**变体修好 ").append(fixed).append(" 条，弄坏 ").append(broken).append(" 条。**");
        text.append("净值为正也不等于该合入——先看弄坏的那几条落在哪个维度：");
        text.append("按 §4 的双层定位，`permission`/`masking` 属于安全门禁层，弄坏一条就是否决项。\n\n");

        text.append("| id | dimension | 基线 | 变体 | 结论 | 轮次 基线→变体 |\n");
        text.append("|---|---|---|---|---|---|\n");
        for (String id : ids) {
            CaseDelta d = delta(id, left.get(id), right.get(id));
            text.append("| ").append(d.id()).append(" | ").append(d.dimension())
                    .append(" | ").append(mark(d.baseline())).append(" | ").append(mark(d.variant()))
                    .append(" | ").append(d.verdict())
                    .append(" | ").append(d.baselineRounds()).append(" → ").append(d.variantRounds())
                    .append(" |\n");
        }
        return text.toString();
    }

    private static CaseDelta delta(String id, GoldenTaskReport.GoldenObservation left,
                                   GoldenTaskReport.GoldenObservation right) {
        return new CaseDelta(id,
                left != null ? left.dimension() : right.dimension(),
                left != null ? left.passed() : null,
                right != null ? right.passed() : null,
                left != null ? left.rounds() : 0,
                right != null ? right.rounds() : 0,
                right != null ? right.reason() : "");
    }

    private static Map<String, GoldenTaskReport.GoldenObservation> byId(GoldenTaskReport report) {
        return report.observations().stream().collect(Collectors.toMap(
                GoldenTaskReport.GoldenObservation::id, Function.identity(),
                (first, duplicate) -> first, java.util.LinkedHashMap::new));
    }

    private static String passRate(GoldenTaskReport report) {
        long passed = report.observations().stream()
                .filter(GoldenTaskReport.GoldenObservation::passed).count();
        return passed + "/" + report.observations().size();
    }

    private static String mark(Boolean passed) {
        if (passed == null) return "—";
        return passed ? "✅" : "❌";
    }
}
