package com.agenttrail.loop.model;

/**
 * 模型把"思考过程"交付给调用方的方式——各厂商做法不同，装配 Agent 时按所用模型选择。
 */
public enum ThinkingMode {

    /** 模型不产出思考过程，或者不需要把它单独展示。 */
    DISABLED,

    /** 思考过程和正文混在同一个 content 字段里，用 {@code <think>} 标签分隔（如 MiniMax）。 */
    THINK_TAG,

    /** 思考过程在独立的 reasoning_content 字段里，content 字段始终是干净正文（如 DeepSeek/Qwen）。 */
    REASONING_CONTENT
}
