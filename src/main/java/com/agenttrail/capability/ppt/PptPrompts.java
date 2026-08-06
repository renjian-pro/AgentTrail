package com.agenttrail.capability.ppt;

/**
 * PPT 生成各 LLM 驱动状态的提示词（issue #24）——REQUIREMENT/OUTLINE/SCHEMA 三个状态各一段，
 * 风格上和 {@code DeepResearchPrompts} 保持一致：结构化输出的格式指令由
 * {@link com.agenttrail.loop.model.OutputType} 机制自动追加在问题末尾，这里的常量本身不含
 * 格式指令文本。
 */
public final class PptPrompts {

    private PptPrompts() {
    }

    /**
     * SEARCH 状态（issue #29）：和 {@code DeepResearchPrompts.EXECUTE} 同一种约束——
     * 必须真的调用联网搜索工具，不能凭模型自己的已有知识直接编答案，输出只保留工具真实
     * 返回的事实，不做延伸分析。用法也和 {@code EXECUTE} 一样是简单前缀拼接（不是
     * {@code String.formatted}）：{@link com.agenttrail.capability.ppt.strategy.SearchStrategy}
     * 把这段常量和一条具体的检索指令（拼上需求的 topic/audience 生成）直接拼在一起。
     */
    public static final String SEARCH = """
            你是【PPT 素材检索专家】。你正在为一份 PPT 收集背景资料，你的输出将作为后续大纲
            规划阶段的事实依据。

            执行要求（必须遵守）：
            - 必须调用联网搜索工具获取信息，不能凭已有知识直接回答
            - 只能基于搜索工具真实返回的内容作答，如实保留不确定或有冲突的信息
            - 只提取和表述工具返回的关键事实、数据、案例，不做延伸分析或价值判断
            - 用简洁的中文归纳成一段素材文本，不需要罗列搜索过程

            当前检索指令：
            """;

    public static final String REQUIREMENT = """
            你是【PPT 需求分析专家】。基于用户的原始需求描述，提炼出结构化的 PPT 制作需求。

            ## 输出字段说明
            - title：这份 PPT 的标题，简洁有力，不超过 20 字
            - topic：核心主题，一句话概括要讲什么
            - audience：目标受众；用户没有明确说明时，结合内容合理推断一个（比如"团队内部汇报"）
            - slideCount：建议的内容页数（不含标题页），2-5 之间的整数，用户没有说明时按内容量合理估算
            - tone：语言风格；用户没有明确要求时给"专业简洁"

            用户的原始需求：
            """;

    /** {@code %s} 是需求摘要 + 素材，OUTLINE 需要看到 REQUIREMENT 和 SEARCH 两个状态的产出。 */
    public static final String OUTLINE = """
            你是【PPT 大纲规划专家】。基于下面的制作需求和检索到的素材，规划这份 PPT 的内容大纲。

            ## 输出字段说明
            - deckTitle：封面主标题
            - deckSubtitle：封面副标题，一句话点出受众/场景
            - slides：内容页列表，每页一个 title（该页小标题）+ bullets（3-5 条要点，每条一句话，
              不要写成完整段落）

            ## 重要原则
            - 页数尽量贴近需求里的 slideCount，不要为了凑数写空洞的要点
            - 只基于下面给出的需求和素材规划内容，不要编造需求里没有提到的事实

            %s
            """;

    /** {@code %s} 是 OUTLINE 的产出，同时把模板 shape 的字数上限写进 Prompt（软约束，见踩坑点 #53）。 */
    public static final String SCHEMA = """
            你是【PPT Schema 生成专家】。基于下面的大纲，把内容整理成填入模板的最终文字——
            这一步要考虑目标模板每个文本框的字数上限，尽量在上限内把话说完整，但不是硬性保证
            （超限部分渲染时会被程序截断兜底，你只需要"尽量控制在范围内"）。

            ## 输出字段说明
            - titleText：封面主标题文字，不超过 %d 字
            - subtitleText：封面副标题文字，不超过 %d 字
            - contentSlides：内容页列表，数量和顺序与大纲的 slides 一一对应；每项：
              - slideTitleText：该页标题，不超过 %d 字
              - slideBodyText：该页正文，把该页的全部要点合并成一段结构化文本（每条要点前加
                "• "、用换行分隔），不超过 %d 字

            大纲：
            """.formatted(
            PptTemplateSpec.TITLE_FONT_LIMIT,
            PptTemplateSpec.SUBTITLE_FONT_LIMIT,
            PptTemplateSpec.CONTENT_TITLE_FONT_LIMIT,
            PptTemplateSpec.CONTENT_BODY_FONT_LIMIT);
}
