package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptFieldType;
import com.agenttrail.capability.ppt.PptOutline;
import com.agenttrail.capability.ppt.PptOutlineSlide;
import com.agenttrail.capability.ppt.PptPageType;
import com.agenttrail.capability.ppt.PptTemplateField;
import com.agenttrail.capability.ppt.PptTemplateRef;
import com.agenttrail.capability.ppt.PptTemplateRegistry;
import com.agenttrail.capability.ppt.PptTemplateStatus;
import com.agenttrail.capability.ppt.PptTemplateVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaStrategyTest {

    @Test
    void degradesToValidLegacySlidesWhenDynamicPagesExceedThePinnedTemplateContract() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {
                  "titleText":"AI Agent 技术原理精要",
                  "subtitleText":"面向技术开发者",
                  "contentSlides":[{
                    "slideTitleText":"总体架构：三大核心组件",
                    "slideBodyText":"• 规划\n• 记忆\n• 工具使用"
                  }],
                  "coverImageUrl":null,
                  "pages":[
                    {
                      "pageId":"cover-1",
                      "pageType":"COVER",
                      "templatePageRef":"COVER",
                      "fields":{
                        "title_text":{"type":"TEXT","text":"AI Agent 技术原理精要","artifactId":null,"value":null}
                      },
                      "speakerNotes":""
                    },
                    {
                      "pageId":"content-1",
                      "pageType":"COMPARE",
                      "templatePageRef":"CONTENT",
                      "fields":{
                        "slide_title_text":{"type":"TEXT","text":"总体架构：三大核心组件","artifactId":null,"value":null},
                        "slide_body_text":{"type":"TABLE","text":null,"artifactId":null,
                          "value":{"columns":["组件","功能"],"rows":[["规划","任务分解"]]}}
                      },
                      "speakerNotes":""
                    }
                  ],
                  "templateId":"default",
                  "templateVersion":"1"
                }
                """)));
        PptTemplateVersion template = defaultTemplate();
        PptTemplateRegistry registry = mock(PptTemplateRegistry.class);
        when(registry.validate("default", "1")).thenReturn(template);
        SchemaStrategy strategy = new SchemaStrategy(new AgentLoopExecutor(model, List.of(), 3), registry);
        PptOutline outline = new PptOutline("AI Agent 技术原理精要", "面向技术开发者",
                List.of(new PptOutlineSlide("总体架构：三大核心组件", List.of("规划", "记忆", "工具使用"))));

        PptGenerationContext result = strategy.execute(PptGenerationContext.initial("conv-1", "生成技术汇报")
                .withOutline(outline).withTemplateRef(PptTemplateRef.from(template)));

        assertThat(result.schema().pages()).isEmpty();
        assertThat(result.schema().contentSlides()).singleElement()
                .satisfies(slide -> assertThat(slide.slideTitleText()).isEqualTo("总体架构：三大核心组件"));
        assertThat(result.warnings()).singleElement()
                .satisfies(warning -> {
                    assertThat(warning.code()).isEqualTo("PPT_DYNAMIC_SCHEMA_DEGRADED");
                    assertThat(warning.affectedPageIds()).containsExactly("cover-1", "content-1");
                });
    }

    @Test
    void givesTheModelThePinnedTemplateAndExplicitPptFieldObjectContract() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {
                  "titleText":"通用PPT制作需求",
                  "subtitleText":"团队内部汇报",
                  "contentSlides":[{"slideTitleText":"实施与反馈","slideBodyText":"先评审，再调整"}],
                  "coverImageUrl":null,
                  "pages":[
                    {
                      "pageId":"cover-1",
                      "pageType":"COVER",
                      "templatePageRef":"COVER",
                      "fields":{
                        "title_text":{"type":"TEXT","text":"通用PPT制作需求","artifactId":null,"value":null},
                        "subtitle_text":{"type":"TEXT","text":"团队内部汇报","artifactId":null,"value":null}
                      },
                      "speakerNotes":"介绍汇报目标"
                    },
                    {
                      "pageId":"content-1",
                      "pageType":"CONTENT",
                      "templatePageRef":"CONTENT",
                      "fields":{
                        "slide_title_text":{"type":"TEXT","text":"实施与反馈","artifactId":null,"value":null},
                        "slide_body_text":{"type":"TEXT","text":"先评审，再调整","artifactId":null,"value":null}
                      },
                      "speakerNotes":"说明执行步骤"
                    }
                  ],
                  "templateId":"default",
                  "templateVersion":"1"
                }
                """)));
        PptTemplateVersion template = defaultTemplate();
        PptTemplateRegistry registry = mock(PptTemplateRegistry.class);
        when(registry.validate("default", "1")).thenReturn(template);
        SchemaStrategy strategy = new SchemaStrategy(new AgentLoopExecutor(model, List.of(), 3), registry);

        PptOutline outline = new PptOutline("通用PPT制作需求", "团队内部汇报",
                List.of(new PptOutlineSlide("实施与反馈", List.of("先评审", "再调整"))));
        PptGenerationContext input = PptGenerationContext.initial("conv-1", "制作一份内部汇报")
                .withOutline(outline).withTemplateRef(PptTemplateRef.from(template));

        PptGenerationContext result = strategy.execute(input);

        assertThat(result.schema().pages()).hasSize(2);
        assertThat(result.schema().pages().get(0).fields().get("title_text").text())
                .isEqualTo("通用PPT制作需求");
        String promptSent = model.messagesAtRound(0).get(1).getText();
        assertThat(promptSent)
                .contains("模板 ID：default")
                .contains("模板版本：1")
                .contains("title_text：type=TEXT，required=true，maxChars=30")
                .contains("\"type\": \"TEXT\"")
                .contains("\"artifactId\": null")
                .contains("fields 的每个值都必须是对象，禁止直接填写字符串");
    }

    private static PptTemplateVersion defaultTemplate() {
        return new PptTemplateVersion("default", "1", "默认模板", "测试模板",
                Set.of("business"), Set.of(PptPageType.COVER, PptPageType.CONTENT), Map.of(
                        "title_text", new PptTemplateField("title_text", PptFieldType.TEXT, true, 30),
                        "subtitle_text", new PptTemplateField("subtitle_text", PptFieldType.TEXT, false, 60),
                        "slide_title_text", new PptTemplateField("slide_title_text", PptFieldType.TEXT, true, 30),
                        "slide_body_text", new PptTemplateField("slide_body_text", PptFieldType.TEXT, true, 400)),
                "artifact", "checksum", PptTemplateStatus.ACTIVE, 1L, "template.pptx");
    }

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

        // index 0 是 AgentLoopExecutor 无条件注入的当前日期系统消息，真正的 prompt 紧跟在它后面
        String promptSent = model.messagesAtRound(0).get(1).getText();
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

        // index 0 是 AgentLoopExecutor 无条件注入的当前日期系统消息，真正的 prompt 紧跟在它后面
        String promptSent = model.messagesAtRound(0).get(1).getText();
        assertThat(promptSent).as("修改指令要真的传给模型，不能被丢在半路").contains("把标题改成《新标题》");
    }
}
