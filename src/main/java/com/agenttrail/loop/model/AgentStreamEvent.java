package com.agenttrail.loop.model;

/**
 * 手写 ReAct 循环对外输出的统一流式事件协议，一个事件对应 SSE 的一个 {@code data:} 帧。
 *
 * <p>用 sealed interface 而不是"带 type 字段的通用 DTO"：变体是封闭的，
 * 消费侧用 switch 模式匹配时编译器能检查穷尽性，将来新增事件类型不会被漏掉。
 *
 * <p>变体随对应机制逐步补齐（见 roadmap Phase 0 各子项）：思考过程、待办进度、
 * 分阶段输出、暂停/恢复等会在各自的 ticket 里加入。
 */
public sealed interface AgentStreamEvent {

    /** 模型正文文本。 */
    record Text(String content) implements AgentStreamEvent {
    }

    /** 模型的思考过程，与正文分开投递，供前端折叠展示。 */
    record Thinking(String content) implements AgentStreamEvent {
    }

    /** 某个工具即将执行，携带重组完成的原始参数，供前端展示"正在做什么"。 */
    record ToolStart(String toolName, String toolCallId, String arguments) implements AgentStreamEvent {
    }

    /** 某个工具执行完毕，携带原始返回内容。 */
    record ToolEnd(String toolName, String toolCallId, String result) implements AgentStreamEvent {
    }

    /** 可恢复或不可恢复的错误。{@code code} 供前端做分类处理，不是给人看的。 */
    record Error(String code, String message) implements AgentStreamEvent {
    }

    /** 整轮对话结束（无论是正常给出答案还是被中断），流随即关闭。 */
    record Complete(String conversationId) implements AgentStreamEvent {
    }
}
