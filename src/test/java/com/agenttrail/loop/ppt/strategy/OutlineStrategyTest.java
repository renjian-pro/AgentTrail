package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

class OutlineStrategyTest {

    @Test
    void parsesTheOutlineAndFeedsRequirementAndMaterialsIntoThePrompt() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"deckTitle":"Spring AI Agent 实战","deckSubtitle":"面向团队内部分享",
                 "slides":[
                   {"title":"为什么手写 ReAct Loop","bullets":["绕开 ChatClient 差异","掌控流式细节"]},
                   {"title":"模板填充生成 PPT","bullets":["设计与内容解耦","Python 渲染"]}
                 ]}
                """)));
        OutlineStrategy strategy = new OutlineStrategy(new AgentLoopExecutor(model, List.of(), 3));

        PptGenerationContext input = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("Spring AI Agent 实战", "手写 ReAct Loop", "团队内部分享", 2, "专业简洁"))
                .withSearchMaterials(List.of("素材一：关于 ReAct Loop 的背景", "素材二：模板填充相关资料"));

        PptGenerationContext result = strategy.execute(input);

        assertThat(result.outline().deckTitle()).isEqualTo("Spring AI Agent 实战");
        assertThat(result.outline().slides()).hasSize(2);
        assertThat(result.outline().slides().get(0).bullets()).contains("绕开 ChatClient 差异");

        String promptSent = model.messagesAtRound(0).get(0).getText();
        assertThat(promptSent).contains("手写 ReAct Loop");
        assertThat(promptSent).contains("素材一：关于 ReAct Loop 的背景");
    }
    @Test
    void acceptsOutlineJsonWrappedInAContentString() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"content":"{\\"deckTitle\\":\\"Wrapped\\",\\"deckSubtitle\\":\\"Subtitle\\",\\"slides\\":[{\\"title\\":\\"Slide\\",\\"bullets\\":[\\"One\\"]}]}"}
                """)));
        OutlineStrategy strategy = new OutlineStrategy(new AgentLoopExecutor(model, List.of(), 3));

        PptGenerationContext result = strategy.execute(minimalContext());

        assertThat(result.outline().deckTitle()).isEqualTo("Wrapped");
        assertThat(result.outline().slides().getFirst().title()).isEqualTo("Slide");
    }

    @Test
    void acceptsDuplicateSlideFieldsFromModelJson() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"deckTitle":"Duplicate","deckSubtitle":"Subtitle","slides":[{"title":"First","bullets":["One"],"title":"Last"}]}
                """)));
        OutlineStrategy strategy = new OutlineStrategy(new AgentLoopExecutor(model, List.of(), 3));

        PptGenerationContext result = strategy.execute(minimalContext());

        assertThat(result.outline().deckTitle()).isEqualTo("Duplicate");
        assertThat(result.outline().slides().getFirst().title()).isEqualTo("Last");
    }

    private static PptGenerationContext minimalContext() {
        return PptGenerationContext.initial("conv-1", "make a deck")
                .withRequirement(new PptRequirement("Deck", "Topic", "Audience", 1, "Concise"))
                .withSearchMaterials(List.of());
    }
}
