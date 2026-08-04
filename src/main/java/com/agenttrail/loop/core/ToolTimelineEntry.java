package com.agenttrail.loop.core;

/**
 * 一次工具调用在 {@code agent_session.timeline} 里的落库形状，和 {@code StageOutput} 共用
 * 同一个数组、靠 {@code type} 区分（见 {@code CapabilityConversationService}）。
 *
 * <p>可变而非 record：{@code ToolStart} 先建出这个条目（这时还没有结果），{@code ToolEnd}
 * 到达时原地补上 {@code result}——同一次工具调用不应该在时间线里拆成两条。
 */
final class ToolTimelineEntry {

    static final String TYPE = "ToolCall";

    public final String type = TYPE;
    public final String toolName;
    public final String toolCallId;
    public final String arguments;
    public String result;

    ToolTimelineEntry(String toolName, String toolCallId, String arguments) {
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.arguments = arguments;
    }
}
