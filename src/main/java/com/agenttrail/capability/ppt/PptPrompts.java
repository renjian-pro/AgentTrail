package com.agenttrail.capability.ppt;

import com.agenttrail.loop.prompt.PromptRegistry;

/**
 * PPT 生成各模型驱动状态的提示词（issue #24）——CLARIFY/REQUIREMENT/SEARCH/OUTLINE/SCHEMA/IMAGE 各一段，
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

    /** 澄清判定输出"信息不足"时的固定标记——和 {@code DeepResearchPrompts.NEEDS_INFO_MARKER} 同一个串，
     *  两条能力各自持有一份常量而不是共享，是因为提示词正文各自独立，标记跟着正文走。 */
    public static final String NEEDS_INFO_MARKER = "【需要补充信息】";

    /** 澄清判定输出"信息充足"时的固定标记，对应 {@code DeepResearchPrompts.READY_MARKER}。 */
    public static final String READY_MARKER = "【开始生成】";

    /** 任务创建前的四项需求预检；未确认时只返回普通会话追问，不进入 PPT 状态机。 */
    public static final String PREFLIGHT = PROMPTS.text("ppt.preflight");

    /**
     * CLARIFY 状态：只判断需求够不够清晰，不生成任何 PPT 内容。两个 {@code %s} 依次是
     * {@link #NEEDS_INFO_MARKER}/{@link #READY_MARKER}——标记由代码注入而不是写死在正文里，
     * 改标记时不会出现"提示词里是一个串、判定代码里是另一个串"的静默失配。
     */
    public static final String CLARIFICATION = PROMPTS.text("ppt.clarification")
            .formatted(NEEDS_INFO_MARKER, READY_MARKER);

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

    /** IMAGE 状态使用的文生图提示模板，依次注入主题、受众和全局视觉规划。 */
    public static final String IMAGE = PROMPTS.text("ppt.image");

    /** {@code %s} 是 OUTLINE 的产出，同时把模板 shape 的字数上限写进 Prompt（软约束，见踩坑点 #53）。 */
    public static final String SCHEMA = PROMPTS.text("ppt.schema").formatted(
            PptTemplateSpec.TITLE_FONT_LIMIT,
            PptTemplateSpec.SUBTITLE_FONT_LIMIT,
            PptTemplateSpec.CONTENT_TITLE_FONT_LIMIT,
            PptTemplateSpec.CONTENT_BODY_FONT_LIMIT);
}
