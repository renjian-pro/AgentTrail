package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptOutline;
import com.agenttrail.loop.ppt.PptOutlineSlide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaStrategyTest {

    @Test
    void parsesTheSchemaAndFeedsTheOutlineIntoThePrompt() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"titleText":"Spring AI Agent 实战","subtitleText":"面向团队内部分享",
                 "contentSlides":[
                   {"slideTitleText":"为什么手写 ReAct Loop","slideBodyText":"绕开 ChatClient 差异"},
                   {"slideTitleText":"模板填充生成 PPT","slideBodyText":"设计与内容解耦"}
                 ]}
                """)));
        SchemaStrategy strategy = new SchemaStrategy(new AgentLoopExecutor(model, List.of(), 3));

        PptOutline outline = new PptOutline("Spring AI Agent 实战", "面向团队内部分享", List.of(
                new PptOutlineSlide("为什么手写 ReAct Loop", List.of("绕开 ChatClient 差异")),
                new PptOutlineSlide("模板填充生成 PPT", List.of("设计与内容解耦"))));
        PptGenerationContext input = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT").withOutline(outline);

        PptGenerationContext result = strategy.execute(input);

        assertThat(result.schema().titleText()).isEqualTo("Spring AI Agent 实战");
        assertThat(result.schema().contentSlides()).hasSize(2);
        assertThat(result.schema().contentSlides().get(1).slideTitleText()).isEqualTo("模板填充生成 PPT");

        String promptSent = model.messagesAtRound(0).get(0).getText();
        assertThat(promptSent).contains("为什么手写 ReAct Loop");
        assertThat(promptSent).contains("绕开 ChatClient 差异");
    }

    @Test
    void feedsUserRequirementIntoThePromptSoAModifyInstructionActuallyTakesEffect() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"titleText":"新标题","subtitleText":"副标题",
                 "contentSlides":[{"slideTitleText":"页面 1","slideBodyText":"内容"}]}
                """)));
        SchemaStrategy strategy = new SchemaStrategy(new AgentLoopExecutor(model, List.of(), 3));

        PptOutline outline = new PptOutline("旧标题", "旧副标题",
                List.of(new PptOutlineSlide("页面 1", List.of("要点"))));
        // MODIFY 分支（issue #32）不重新执行 REQUIREMENT/OUTLINE，只是把 userRequirement 换成这次
        // 的修改指令——SCHEMA 状态是唯一还会重新执行的 LLM 调用，必须能看到这条指令
        PptGenerationContext input = PptGenerationContext.initial("conv-1", "把标题改成《新标题》")
                .withOutline(outline);

        strategy.execute(input);

        String promptSent = model.messagesAtRound(0).get(0).getText();
        assertThat(promptSent).as("修改指令要真的传给模型，不能被丢在半路").contains("把标题改成《新标题》");
    }
}
