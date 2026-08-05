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

        // index 0 是 AgentLoopExecutor 无条件注入的当前日期系统消息，真正的 prompt 紧跟在它后面
        String promptSent = model.messagesAtRound(0).get(1).getText();
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

    @Test
    void parsesOutlineWhenBulletsContainUnescapedQuotes() {
        // 回归测试：模型在 bullets 数组的字符串值里直接写英文双引号（"丑角"、
        // "积极热情勤奋"）导致整个 JSON 损坏。修复后 JsonRepair 应当能把字符串内
        // 裸引号替换为中文引号让 JSON 重新能解析，OutlineStrategy 应当能从损坏输入中
        // 拿到正确的大纲结构
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {
                  "deckTitle": "小丑入职秀",
                  "deckSubtitle": "——以幽默与真诚，开启我的团队首秀",
                  "slides": [
                    {
                      "title": "为什么是小丑？",
                      "bullets": [
                        "小丑是喜剧的化身，用自嘲与幽默带来欢笑",
                        "戏剧原型中的"丑角"跳脱常规，独具魅力",
                        "打破"积极热情勤奋"的套路，用反差制造记忆点",
                        "我选择欢乐小丑，拒绝暗黑联想，只为传递亲和力"
                      ]
                    },
                    {
                      "title": "我的小丑人设",
                      "bullets": [
                        "角色造型：标志性红鼻头+夸张领结，一眼记住我",
                        "言语表达：用梗和自黑代替干巴巴的形容词"
                      ]
                    }
                  ]
                }
                """)));
        OutlineStrategy strategy = new OutlineStrategy(new AgentLoopExecutor(model, List.of(), 3));

        PptGenerationContext result = strategy.execute(minimalContext());

        assertThat(result.outline().deckTitle()).isEqualTo("小丑入职秀");
        assertThat(result.outline().slides()).hasSize(2);
        assertThat(result.outline().slides().getFirst().title()).isEqualTo("为什么是小丑？");
        assertThat(result.outline().slides().getFirst().bullets()).hasSize(4);
        // 裸引号被启发式替换为左中文引号，原意保留
        assertThat(result.outline().slides().getFirst().bullets().get(1)).contains("“丑角”");
        assertThat(result.outline().slides().getFirst().bullets().get(2)).contains("“积极热情勤奋”");
    }

    @Test
    void parsesOutlineEvenWhenRepairWrapsItInAContentField() {
        // 极端兜底：模型既没正确转义，又被 JsonRepair 包成 {"content": "..."}。
        // 这种情况的 unwrap 深度会触达上限并触发宽容提取——应当能拿到 PptOutline 字段
        // 而不是报 "Unrecognized field content"
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("""
                {"content":"{\\n  \\"deckTitle\\": \\"小丑入职秀\\",\\n  \\"deckSubtitle\\": \\"——以幽默与真诚\\",\\n  \\"slides\\": [{\\n    \\"title\\": \\"为什么是小丑？\\",\\n    \\"bullets\\": [\\n      \\"戏剧原型中的\\"丑角\\"跳脱常规\\",\\n      \\"用反差制造记忆点\\"\\n    ]\\n  }]\\n}"}
                """)));
        OutlineStrategy strategy = new OutlineStrategy(new AgentLoopExecutor(model, List.of(), 3));

        PptGenerationContext result = strategy.execute(minimalContext());

        assertThat(result.outline().deckTitle()).isEqualTo("小丑入职秀");
        assertThat(result.outline().slides()).hasSize(1);
        assertThat(result.outline().slides().getFirst().bullets()).hasSize(2);
    }

    private static PptGenerationContext minimalContext() {
        return PptGenerationContext.initial("conv-1", "make a deck")
                .withRequirement(new PptRequirement("Deck", "Topic", "Audience", 1, "Concise"))
                .withSearchMaterials(List.of());
    }
}
