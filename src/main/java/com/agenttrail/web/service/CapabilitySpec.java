package com.agenttrail.web.service;

import com.agenttrail.loop.security.DataProvenancePolicy;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 一个能力包在运行时的装配规格（issue #99）。
 *
 * <p><b>解决什么</b>：{@code AgentLoopExecutorFactory} 里的 {@code forModel}/{@code forModelWithCharts}/
 * {@code forAnalytics}/{@code forInternalOrchestration} 四个方法，方法体是同一段 builder 链的复制。
 * "加一种能力"于是等于"加一个方法"，而每份复制都可能漏传一个协作者——分析执行器缺
 * {@code skillManager} 导致 DataAgent 拿不到自己的 SOP（issue #95），正是这种漏传的产物：
 * 编译器不会报错，只会在运行时表现为某个机制静默失效。
 *
 * <p><b>和 {@link com.agenttrail.runtime.agent.AgentDefinition} 的分工</b>：那个是**可序列化的
 * 声明**（能从 yml/DB 读出来），所以刻意不含任何运行时协作者；这个是它在装配层的落地形态，
 * 持有真实的 {@code ToolCallback} 实例。两者不能合并——合并就等于让声明层反向依赖 {@code loop}，
 * 那正是 Phase -1 刚打破的那条包循环。
 *
 * @param tools                     常驻工具
 * @param maxRounds                 轮次上限
 * @param maxConsecutiveToolFailures 同一工具连续失败多少次提前止损；0 表示不启用
 * @param protectedToolNames        上下文压缩的保护名单——产出不可重建的工具（如图表 URL）要进
 * @param dataProvenancePolicy      消费型工具的数据来源门禁
 * @param persist                   是否落库为用户可见的一轮对话
 * @param userFacing                是否接入 Skill/记忆/文件这些"面向这个用户的一次对话"才有意义的机制
 */
public record CapabilitySpec(
        List<ToolCallback> tools,
        int maxRounds,
        int maxConsecutiveToolFailures,
        List<String> protectedToolNames,
        DataProvenancePolicy dataProvenancePolicy,
        boolean persist,
        boolean userFacing) {

    private static final int DEFAULT_MAX_ROUNDS = 10;

    public CapabilitySpec {
        tools = tools == null ? List.of() : List.copyOf(tools);
        protectedToolNames = protectedToolNames == null ? List.of() : List.copyOf(protectedToolNames);
        dataProvenancePolicy = dataProvenancePolicy == null ? DataProvenancePolicy.DISABLED : dataProvenancePolicy;
    }

    /** 普通对话：文件/搜索/图表这些通用工具，挂 Skill 和记忆，落库。 */
    public static CapabilitySpec chat(List<ToolCallback> tools, List<String> protectedToolNames,
                                      DataProvenancePolicy provenance) {
        return new CapabilitySpec(tools, DEFAULT_MAX_ROUNDS, 0, protectedToolNames, provenance, true, true);
    }

    /**
     * DataAgent：轮次上限翻倍（SQL 分析要探 Schema、查术语、校验、执行、计算，10 轮不够），
     * 且同一工具连续失败 3 次就止损——这是 ReAct 不是 Workflow，SKILL.md 写的重试预算只是给模型的
     * 指导不是强制，不能指望它在撞顶之前自己收敛。
     */
    public static CapabilitySpec analytics(List<ToolCallback> tools, List<String> protectedToolNames,
                                           DataProvenancePolicy provenance) {
        return new CapabilitySpec(tools, 20, 3, protectedToolNames, provenance, true, true);
    }

    /**
     * DeepResearch 这类内部编排的子调用：不落库、不挂 Skill/记忆/文件。它们不是用户在应用层面
     * 发起的一轮对话——落进 {@code agent_session} 只会把会话历史侧栏污染成一堆内部子提示词
     * （真实发生过，一次请求能留下十几条上万字的垃圾行）。
     */
    public static CapabilitySpec internalOrchestration(List<ToolCallback> tools) {
        return new CapabilitySpec(tools, DEFAULT_MAX_ROUNDS, 0, List.of(),
                DataProvenancePolicy.DISABLED, false, false);
    }
}
