package com.agenttrail.capability.ppt;

/**
 * REQUIREMENT 状态的产出：把用户一段自然语言原始需求，提炼成结构化的 PPT 制作需求
 * （issue #24）。{@link com.agenttrail.loop.model.OutputType} 结构化输出的目标类型。
 *
 * @param title      PPT 标题
 * @param topic      核心主题，一句话概括
 * @param audience   目标受众
 * @param slideCount 建议内容页数（不含标题页）——只是规划参考，OUTLINE 状态据此规划页数，
 *                   不是渲染侧的强约束：真正渲染多少页由 OUTLINE 实际产出的 slides 数量决定
 * @param tone       语言风格
 */
public record PptRequirement(String title, String topic, String audience, int slideCount, String tone) {
}
