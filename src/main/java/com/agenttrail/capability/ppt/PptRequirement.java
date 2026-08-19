package com.agenttrail.capability.ppt;

/**
 * REQUIREMENT 状态的产出：把用户一段自然语言原始需求，提炼成结构化的 PPT 制作需求
 * （issue #24）。{@link com.agenttrail.runtime.api.OutputType} 结构化输出的目标类型。
 *
 * @param title      PPT 标题
 * @param topic      核心主题，一句话概括
 * @param audience   目标受众
 * @param slideCount 建议内容页数（不含标题页）——只是规划参考，OUTLINE 状态据此规划页数，
 *                   不是渲染侧的强约束：真正渲染多少页由 OUTLINE 实际产出的 slides 数量决定
 * @param tone       语言风格
 */
public record PptRequirement(String title, String topic, String audience, int slideCount, String tone) {

    public static final int DEFAULT_SLIDE_COUNT = 10;
    public static final int MAX_SLIDE_COUNT = 50;
    private static final java.util.Set<String> INVALID_TOPICS = java.util.Set.of(
            "ppt", "做ppt", "做一个ppt", "做一份ppt", "生成ppt", "这个", "那个", "刚才那个",
            "生成", "生成吧", "开始生成", "随便");

    /** 主题是开工的唯一硬门禁；其他字段可以从上下文推断或使用稳定默认值。 */
    public boolean hasValidTopic() {
        if (topic == null || topic.isBlank()) return false;
        String normalized = topic.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]+", "");
        return normalized.length() >= 2 && !INVALID_TOPICS.contains(normalized);
    }

    /**
     * 把非门禁字段收敛成后续阶段可以直接使用的稳定值。这里用确定性默认值兜底，避免提示词的一次
     * 波动让后续 Search/Outline 出现 null；模型仍可根据会话提供更准确的受众、页数和风格。
     */
    public PptRequirement normalized() {
        if (!hasValidTopic()) return this;
        String normalizedTopic = topic.strip();
        int normalizedSlideCount = slideCount <= 0 ? DEFAULT_SLIDE_COUNT
                : Math.min(slideCount, MAX_SLIDE_COUNT);
        return new PptRequirement(
                defaultIfBlank(title, normalizedTopic),
                normalizedTopic,
                defaultIfBlank(audience, "通用受众"),
                normalizedSlideCount,
                defaultIfBlank(tone, "专业简洁"));
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
