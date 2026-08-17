package com.agenttrail.capability.deepresearch;

import com.agenttrail.loop.prompt.PromptRegistry;

/**
 * DeepResearch 各阶段的提示词（issue #25）。需求澄清和研究主题生成两段移植自参考实现
 * （dodo-agent {@code PlanExecutePrompts}）——判定标记、判定倾向（"能推断方向就直接开始，
 * 不追问细节"）都是原样保留，这条本身就是"和 PPT 需求澄清同一套解法"的复用证据。
 *
 * <p>PLAN 提示词现在会生成 {@code order} 分层字段（issue #34）。CRITIQUE（issue #35）是结构化
 * 通过/不通过判定，不通过时的反馈文本会被拼进下一轮的 PLAN 调用，让模型针对性补充而不是
 * 盲目重跑一遍一样的任务。
 */
final class DeepResearchPrompts {
    /** 提示词正文外置在 {@code resources/prompts/}（issue #100）：改一句不用动代码，
     *  且每一版都有可写进 trace 的 {@code id@version#hash} 标识，Golden 分数变化才归因得了。 */
    private static final PromptRegistry PROMPTS = PromptRegistry.shared();


    private DeepResearchPrompts() {
    }

    static final String NEEDS_INFO_MARKER = "【需要补充信息】";
    static final String READY_MARKER = "【开始研究】";

    static final String CLARIFICATION = PROMPTS.text("deepresearch.clarification").formatted(NEEDS_INFO_MARKER, READY_MARKER);

    static final String TOPIC_GENERATION = PROMPTS.text("deepresearch.topic_generation");

    /** 结构化输出格式指令由 issue #18 的 OutputType 机制在 {@code AgentLoopExecutor} 里自动追加。 */
    static final String PLAN = PROMPTS.text("deepresearch.plan");

    static final String EXECUTE = PROMPTS.text("deepresearch.execute");

    /** 结构化输出格式指令由 issue #18 的 OutputType 机制在 {@code AgentLoopExecutor} 里自动追加，
     * 不需要在这段提示词里手动占位——调用方按 {@code CRITIQUE + 研究主题 + 检索结果} 拼接。 */
    static final String CRITIQUE = PROMPTS.text("deepresearch.critique");

    static final String SUMMARIZE = PROMPTS.text("deepresearch.summarize");
}
