package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 {@link ScriptedChatModel} 驱动的确定性单测——只验证 {@link SearchStrategy} 自己的编排逻辑
 * （真的走了几次 {@link AgentLoopExecutor#call}、每次检索指令里有没有带上需求的主题/受众，
 * 让下游模型有据可查而不是自由发挥），不断言真实联网搜索返回了什么内容——那是
 * {@code SearchStrategyIT} 的职责（issue #29：真实 Tavily key + 真实模型 key 跑一次
 * "给一个 PPT 主题 → 收集到真实资料 → 资料进入后续 OUTLINE 阶段"）。
 */
class SearchStrategyTest {

    @Test
    void callsTheSearchEnabledExecutorForEachInstructionAndCollectsMaterialsIntoTheContext() {
        ScriptedChatModel searchModel = new ScriptedChatModel(
                List.of(text("背景资料：Spring AI Agent 是一种自主执行任务的智能体架构")),
                List.of(text("补充资料：2025 年 Agent 相关的行业趋势和代表性案例")));
        SearchStrategy strategy = new SearchStrategy(new AgentLoopExecutor(searchModel, List.of(), 5));

        PptGenerationContext context = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("标题", "Spring AI Agent", "团队内部", 2, "专业简洁"));

        PptGenerationContext result = strategy.execute(context);

        assertThat(result.searchMaterials()).containsExactly(
                "背景资料：Spring AI Agent 是一种自主执行任务的智能体架构",
                "补充资料：2025 年 Agent 相关的行业趋势和代表性案例");
        assertThat(searchModel.roundCount())
                .as("两条检索指令各自独立触发一次 AgentLoopExecutor 的 ReAct 子循环调用")
                .isEqualTo(2);
    }

    @Test
    void groundsEachSearchInstructionInTheRequirementTopicAndAudience() {
        ScriptedChatModel searchModel = new ScriptedChatModel(
                List.of(text("材料1")),
                List.of(text("材料2")));
        SearchStrategy strategy = new SearchStrategy(new AgentLoopExecutor(searchModel, List.of(), 5));

        PptGenerationContext context = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("标题", "Spring AI Agent", "团队内部汇报", 2, "专业简洁"));

        strategy.execute(context);

        String firstInstruction = renderedText(searchModel.messagesAtRound(0));
        String secondInstruction = renderedText(searchModel.messagesAtRound(1));
        assertThat(firstInstruction).contains("Spring AI Agent").contains("团队内部汇报");
        assertThat(secondInstruction).contains("Spring AI Agent");
    }

    private static String renderedText(List<org.springframework.ai.chat.messages.Message> messages) {
        return messages.stream().map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);
    }
}
