package com.agenttrail.loop.model;

import java.util.Map;

/**
 * 单次循环执行的运行时参数，承载"双通道"动态参数（踩坑点 #59）。
 *
 * <p>两条通道的区别在于**模型看不看得见**：
 * <ul>
 *   <li>会话标识这类（{@code conversationId}/{@code userId}）：Runtime 自己用，不进 prompt
 *   <li>{@code toolParams}：模型完全看不见，由 Runtime 在工具执行前强制注入并覆盖模型给的值，
 *       并按目标工具的 inputSchema 白名单过滤——详见 {@code ToolParamInjector}
 * </ul>
 *
 * <p>userId 之所以必须走不可见通道：让模型自己往工具参数里填 userId，等于把越权的口子
 * 交给一个可以被用户诱导的组件。数据权限相关的能力包都依赖这个机制。
 *
 * @param conversationId 会话标识，跨轮可见
 * @param userId         当前用户，权限判定的主体
 * @param toolParams     模型不可见、执行前强制注入的系统级参数
 * @param outputType     期望的结构化输出类型（issue #18）；为 null 表示不启用，
 *                       循环既不注入格式指令也不做 JSON 修复，行为和没有这个机制时完全一致
 */
public record RunnableParams(String conversationId, String userId, Map<String, Object> toolParams,
                             OutputType outputType) {

    public RunnableParams {
        toolParams = (toolParams == null) ? Map.of() : Map.copyOf(toolParams);
    }

    /** 不需要注入任何系统级参数、也不需要结构化输出时的简写。 */
    public RunnableParams(String conversationId, String userId) {
        this(conversationId, userId, Map.of(), null);
    }

    /** 需要系统级参数、但不需要结构化输出时的简写。 */
    public RunnableParams(String conversationId, String userId, Map<String, Object> toolParams) {
        this(conversationId, userId, toolParams, null);
    }
}
