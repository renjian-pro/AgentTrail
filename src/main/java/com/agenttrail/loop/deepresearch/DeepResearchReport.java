package com.agenttrail.loop.deepresearch;

import java.util.List;

/**
 * 一次 DeepResearch 请求的结果（issue #25）——两种终态，{@link #needsClarification} 为 true 时
 * 只有 {@link #clarifyingQuestion} 有意义，其余字段为空；为 false 时流程走完了完整的
 * 需求澄清（已通过）→ 主题生成 → 顺序任务执行 → 综合报告。
 */
public record DeepResearchReport(
        boolean needsClarification,
        String clarifyingQuestion,
        String researchTopic,
        List<TaskResult> taskResults,
        String report) {

    public static DeepResearchReport needsClarification(String clarifyingQuestion) {
        return new DeepResearchReport(true, clarifyingQuestion, null, List.of(), null);
    }

    public static DeepResearchReport completed(String researchTopic, List<TaskResult> taskResults, String report) {
        return new DeepResearchReport(false, null, researchTopic, taskResults, report);
    }
}
