package com.agenttrail.loop.deepresearch;

/**
 * 结构化输出的目标类型（issue #35，issue #18 机制复用）——{@code passed} 是模型给出的结构化
 * 布尔判定，不是靠解析自由文本猜"通过"两个字在不在里面；{@code feedback} 是不通过时具体缺什么、
 * 下一轮该补什么，通过时可以为空。
 */
public record CritiqueResult(boolean passed, String feedback) {
}
