package com.agenttrail.loop.stageoutput;

/**
 * {@link StageOutputProvider} 可以挂载的生命周期钩子点。
 *
 * <pre>
 * stream() 开始
 *   │
 *   ▼  ← AFTER_START
 * ┌─── 轮次循环 ─────────────────┐
 * │  Thinking / Text              │
 * │  ToolStart ... ToolEnd        │
 * │   ▼  ← AFTER_TOOL_END        │
 * │   └──→ 继续下一轮             │
 * └───────────────────────────────┘
 *   │
 *   ▼
 * Complete
 *   ▲  ← BEFORE_COMPLETE
 * </pre>
 */
public enum StageTiming {

    /** {@code stream()} 发起之后、第一轮模型调用之前。适用场景：欢迎语、初始化提示。 */
    AFTER_START,

    /** 每一轮工具执行批次完成后（一轮可能并发跑多个工具调用，这里是"这一批"跑完，
     *  不是每个工具单独触发一次）。适用场景：实时展示工具结果。 */
    AFTER_TOOL_END,

    /** 最终答案确定之后、{@code Complete} 事件之前。适用场景：引用链接、推荐问题。 */
    BEFORE_COMPLETE
}
