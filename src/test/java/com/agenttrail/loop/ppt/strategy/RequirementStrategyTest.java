package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptGenerationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只验证 {@link RequirementStrategy} 自己的行为（拼什么 prompt、怎么解析模型输出），
 * 用 {@link ScriptedChatModel} 驱动，不碰真实模型——真实调用见 {@code PptGenerationServiceIT}。
 */
class RequirementStrategyTest {

    @Test
    void parsesTheModelJsonIntoAStructuredRequirementAndKeepsEarlierContextFields() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"title":"Spring AI Agent 实战","topic":"手写 ReAct Loop 的工程细节",
                 "audience":"团队内部分享","slideCount":3,"tone":"专业简洁"}
                """)));
        RequirementStrategy strategy = new RequirementStrategy(new AgentLoopExecutor(model, List.of(), 3));
        PptGenerationContext input = PptGenerationContext.initial("conv-1", "帮我做一份关于 Spring AI Agent 的介绍");

        PptGenerationContext result = strategy.execute(input);

        assertThat(result.requirement().title()).isEqualTo("Spring AI Agent 实战");
        assertThat(result.requirement().slideCount()).isEqualTo(3);
        assertThat(result.userRequirement()).isEqualTo(input.userRequirement());
        assertThat(model.messagesAtRound(0).get(0).getText()).contains("帮我做一份关于 Spring AI Agent 的介绍");
    }

    @Test
    void wrapsMalformedModelOutputIntoAClearException() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("这不是合法的 JSON")));
        RequirementStrategy strategy = new RequirementStrategy(new AgentLoopExecutor(model, List.of(), 3));

        assertThatThrownBy(() -> strategy.execute(PptGenerationContext.initial("conv-1", "随便做一个")))
                .isInstanceOf(PptGenerationException.class)
                .hasMessageContaining("REQUIREMENT 状态解析失败");
    }
}
