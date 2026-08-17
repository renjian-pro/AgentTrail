package com.agenttrail.loop.model;

import com.agenttrail.loop.pause.PauseReason;

import java.util.List;

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

    /** 一轮对话已被接收，前端据此固定本次会话标识并切换到生成态。 */
    record AgentStart(String conversationId) implements AgentStreamEvent {
    }

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

    /**
     * 一个 {@code StageOutputProvider} 在某个生命周期钩子点产出的额外输出（issue #16），
     * 比如引用链接、推荐问题——和模型正文是两回事，前端按需单独渲染。
     *
     * @param stage 产出它的 provider 名字，供前端区分来源
     * @param data  provider 自己定义的数据，本类不关心具体形状
     */
    record StageOutput(String stage, Object data) implements AgentStreamEvent {
    }

    /**
     * 待办清单的最新快照，{@code TodoWrite} 工具每次被调用后发一次。
     *
     * <p>{@code items} 来自重新解析那次调用的原始参数，不是从工具的返回文本里提取——
     * 两者独立解析同一份 JSON，工具内部实现改动不会悄悄影响这里的内容。
     */
    record TodoProgress(List<TodoItem> items) implements AgentStreamEvent {
    }

    /**
     * 循环暂停，等待外部处理（HITL 审批或用户中断）后再通过单独的 resume 入口恢复。
     * 流随即关闭——这不是错误，也不是正常完成，是第三种终局。
     */
    record Paused(String conversationId, PauseReason reason, List<PendingTool> pendingTools) implements AgentStreamEvent {

        public Paused {
            pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
        }

        public Paused(String conversationId, PauseReason reason) {
            this(conversationId, reason, List.of());
        }
    }

    /** 只含可展示的脱敏参数；真正执行使用的原始参数只存在服务端暂停快照中。 */
    record PendingTool(String toolCallId, String toolName, String arguments, String riskLevel) {
    }

    /** 可恢复或不可恢复的错误。{@code code} 供前端做分类处理，不是给人看的。 */
    record Error(String code, String message) implements AgentStreamEvent {
    }

    /**
     * 整轮对话结束（无论是正常给出答案还是被中断），流随即关闭。
     *
     * @param turnId 这一轮落库后的主键；没接会话持久化时为 null，前端不指望能拿到它
     */
    record Complete(String conversationId, Long turnId) implements AgentStreamEvent {
    }
}
