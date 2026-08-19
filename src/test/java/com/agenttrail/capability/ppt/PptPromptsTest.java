package com.agenttrail.capability.ppt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PPT 各模型阶段的提示词契约，防止只升级其中一段后其余阶段继续使用含糊指令。 */
class PptPromptsTest {

    @Test
    void clarificationTreatsTheTopicAsTheOnlyHardGate() {
        assertThat(PptPrompts.CLARIFICATION)
                .contains("主题", "页数", "风格", "受众")
                .contains("主题是唯一必须明确")
                .contains("不能因此追问");
    }

    @Test
    void requirementExtractionMustNotInventMissingDecisions() {
        assertThat(PptPrompts.REQUIREMENT)
                .contains("## 角色", "## 任务", "## 输出要求")
                .contains("主题不得虚构")
                .contains("稳定默认值")
                .contains("title", "topic", "audience", "slideCount", "tone");
    }

    @Test
    void searchCollectsTheMaterialNeededByAnOutline() {
        assertThat(PptPrompts.SEARCH)
                .contains("背景信息", "关键数据", "典型案例")
                .contains("自然语言")
                .contains("不要输出任何无关");
    }

    @Test
    void outlineDefinesNarrativeAndPerPageContentWithoutExplanatoryText() {
        assertThat(PptPrompts.OUTLINE)
                .contains("## 角色", "## 任务", "## 输出要求")
                .contains("deckTitle", "deckSubtitle", "slides", "title", "bullets")
                .contains("检索素材")
                .contains("不要输出任何解释");
    }

    @Test
    void schemaPinsTemplateFieldsAndRunsAnOutputSelfCheck() {
        assertThat(PptPrompts.SCHEMA)
                .contains("选定模板契约")
                .contains("字段名", "字段类型", "图片生成提示词")
                .contains("输出前自检")
                .contains("不要输出注释");
    }
}
