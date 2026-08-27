package com.agenttrail.loop.memory;

/**
 * 长期记忆的语义类型（issue #19），覆盖用户身份、偏好、指令和事实四个维度。
 */
public enum MemoryType {

    /** 用户是谁——身份、角色、背景。例："产品经理，擅长数据分析"。 */
    PROFILE,

    /** 用户喜欢什么、习惯什么。例："偏好中文回复"。 */
    PREFERENCE,

    /** 用户要求 Agent 怎么做——纠正/确认后的行为规则。例："不要解释原理直接给答案"。 */
    INSTRUCTION,

    /** 从对话中得知的客观事实。例："系统使用 MySQL 8.0"。 */
    FACT
}
