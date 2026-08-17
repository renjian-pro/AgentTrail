package com.agenttrail.capability.ppt;

import com.agenttrail.loop.prompt.PromptRegistry;

/**
 * PPT 生成各 LLM 驱动状态的提示词（issue #24）——REQUIREMENT/OUTLINE/SCHEMA 三个状态各一段，
 * 风格上和 {@code DeepResearchPrompts} 保持一致：结构化输出的格式指令由
 * {@link com.agenttrail.runtime.api.OutputType} 机制自动追加在问题末尾，这里的常量本身不含
 * 格式指令文本。
 */
public final class PptPrompts {
    /** 提示词正文外置在 {@code resources/prompts/}（issue #100）：改一句不用动代码，
     *  且每一版都有可写进 trace 的 {@code id@version#hash} 标识，Golden 分数变化才归因得了。 */
    private static final PromptRegistry PROMPTS = PromptRegistry.shared();


    private PptPrompts() {
    }

    /**
     * SEARCH 状态（issue #29）：和 {@code DeepResearchPrompts.EXECUTE} 同一种约束——
     * 必须真的调用联网搜索工具，不能凭模型自己的已有知识直接编答案，输出只保留工具真实
     * 返回的事实，不做延伸分析。用法也和 {@code EXECUTE} 一样是简单前缀拼接（不是
     * {@code String.formatted}）：{@link com.agenttrail.capability.ppt.strategy.SearchStrategy}
     * 把这段常量和一条具体的检索指令（拼上需求的 topic/audience 生成）直接拼在一起。
     */
    public static final String SEARCH = PROMPTS.text("ppt.search");

    public static final String REQUIREMENT = PROMPTS.text("ppt.requirement");

    /** {@code %s} 是需求摘要 + 素材，OUTLINE 需要看到 REQUIREMENT 和 SEARCH 两个状态的产出。 */
    public static final String OUTLINE = PROMPTS.text("ppt.outline");

    /** {@code %s} 是 OUTLINE 的产出，同时把模板 shape 的字数上限写进 Prompt（软约束，见踩坑点 #53）。 */
    public static final String SCHEMA = PROMPTS.text("ppt.schema").formatted(
            PptTemplateSpec.TITLE_FONT_LIMIT,
            PptTemplateSpec.SUBTITLE_FONT_LIMIT,
            PptTemplateSpec.CONTENT_TITLE_FONT_LIMIT,
            PptTemplateSpec.CONTENT_BODY_FONT_LIMIT);
}
