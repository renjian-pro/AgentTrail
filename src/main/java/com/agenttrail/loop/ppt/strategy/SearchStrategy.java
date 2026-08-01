package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptGenerationStrategy;
import com.agenttrail.loop.ppt.PptPrompts;
import com.agenttrail.loop.ppt.PptRequirement;
import com.agenttrail.loop.ppt.PptState;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SEARCH 状态（issue #24 骨架 → issue #29 接入真实联网搜索）：真正调用 issue #22 已经接好的
 * Tavily 联网搜索工具收集 PPT 素材，不再是占位/canned 输入。
 *
 * <p>信息收集走自研的 {@link AgentLoopExecutor}（issue #20/#22 已经在用的手写 ReAct 循环）
 * 做联网搜索 + 流式输出，不用 Spring AI 官方的"流式 + 工具调用"组合——参考实现（dodo-agent）
 * 在这个组合场景下实测"稳定性很差，有一些 bug，导致工具无法调用"，退回自研 ReAct Agent 才
 * 解决（见 {@code docs/engineering-pitfalls-and-highlights.md} 踩坑点 #51）。本项目从 issue #17
 * 起手写 loop 就是同一个取舍的延续，不是这里新引入的决策——{@link #execute} 拿到的
 * {@code searchExecutor} 必须是挂了搜索工具的执行器（{@code AgentLoopExecutorFactory
 * .forModel(modelId, true)}，见 {@code PptGenerationConfig}），本类自己不关心工具是怎么挂上去的。
 *
 * <p>固定拆成两条独立的检索指令（背景现状一条、行业趋势/数据/案例一条），分两次调用
 * {@link AgentLoopExecutor#call}——不像 {@code DeepResearchService} 那样先让模型自己规划
 * 检索任务再执行：PPT 素材收集不需要那么重的计划-执行-批判循环，固定两个覆盖面互补的角度
 * 已经足够喂给 OUTLINE 状态，多一层规划只会多引入一次可能解析失败的结构化输出。
 */
public class SearchStrategy implements PptGenerationStrategy {

    private final AgentLoopExecutor searchExecutor;

    public SearchStrategy(AgentLoopExecutor searchExecutor) {
        this.searchExecutor = searchExecutor;
    }

    @Override
    public PptState handledState() {
        return PptState.SEARCH;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        PptRequirement requirement = context.requirement();
        List<String> instructions = List.of(
                "搜索主题「%s」的背景信息：面向%s，检索核心概念、发展现状、典型应用场景。"
                        .formatted(requirement.topic(), requirement.audience()),
                "搜索主题「%s」的补充资料：检索行业趋势、关键数据、代表性案例。"
                        .formatted(requirement.topic()));

        List<String> materials = instructions.stream()
                .map(instruction -> searchExecutor.call(PptPrompts.SEARCH + instruction, freshParams()))
                .toList();
        return context.withSearchMaterials(materials);
    }

    /**
     * 每次调用都用新生成的随机会话 id，不复用 {@code context.conversationId()}——这里踩过一个真实的
     * bug：一开始图省事直接复用 {@code context.conversationId()}（照搬 REQUIREMENT/OUTLINE/SCHEMA
     * 三个状态的写法），两条检索指令背靠背调用同一个执行器时必现
     * {@code AgentCallException(CONCURRENT_EXECUTION, 该会话正在执行中，请稍后再试)}——
     * {@link AgentLoopExecutor#completeRun} 里 {@code taskManager.removeTask(...)} 是在
     * {@code emitComplete()} 之后才调用的，第二次 {@code call()} 有几率在单飞占位真正释放之前
     * 就发起注册。REQUIREMENT/OUTLINE/SCHEMA 从没暴露这个问题，是因为它们各自只调用一次，
     * 且彼此之间夹着别的状态、有真实网络延迟撑开时间窗；SEARCH 这里同一个方法体内背靠背调用
     * 同一个执行器，时间窗几乎是 0，几乎必现。这正是 {@code DeepResearchService} 对每次独立的
     * 检索子任务调用都用 {@code UUID.randomUUID()} 而不是复用外层会话 id 的原因（见其
     * {@code freshParams}）——两条检索指令本来就是内容上独立、不需要共享历史的两次调用，
     * 用独立会话 id 既绕开了这个竞态，语义上也更贴切。
     */
    private static RunnableParams freshParams() {
        return new RunnableParams(UUID.randomUUID().toString(), "ppt-generation", Map.of(), null);
    }
}
