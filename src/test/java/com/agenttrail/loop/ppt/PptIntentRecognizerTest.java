package com.agenttrail.loop.ppt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PptIntentRecognizerTest {

    @Test
    void defaultsToCreateWhenNoKeywordMatches() {
        assertThat(PptIntentRecognizer.recognize("帮我做一份关于 Spring AI 的介绍 PPT"))
                .isEqualTo(PptIntent.CREATE);
    }

    @Test
    void defaultsToCreateForNullMessage() {
        assertThat(PptIntentRecognizer.recognize(null)).isEqualTo(PptIntent.CREATE);
    }

    @Test
    void recognizesResumeKeyword() {
        assertThat(PptIntentRecognizer.recognize("继续生成之前那份 PPT")).isEqualTo(PptIntent.RESUME);
    }

    @Test
    void recognizesModifyKeyword() {
        assertThat(PptIntentRecognizer.recognize("帮我修改这个标题")).isEqualTo(PptIntent.MODIFY);
    }

    @Test
    void resumeMarkerIsRecognizedEvenWithoutAnyResumeKeyword() {
        assertThat(PptIntentRecognizer.recognize("【开始生成PPT】"))
                .as("固定标记本身就足够判定 RESUME，不需要额外命中关键词")
                .isEqualTo(PptIntent.RESUME);
    }

    @Test
    void pauseMarkerOverridesAResumeKeywordThatHappensToBePresent() {
        // 消息里同时出现了"继续生成"这个关键词和暂停标记（比如复制粘贴历史提示文案）——
        // 固定标记的优先级必须压过关键词兜底，不能被误判成 RESUME
        assertThat(PptIntentRecognizer.recognize("【暂停生成PPT】（之前发过：继续生成这份 PPT）"))
                .as("暂停标记命中时明确不是继续，即使文本里夹带了继续类关键词")
                .isEqualTo(PptIntent.CREATE);
    }

    @Test
    void pauseMarkerAloneDoesNotFallThroughToModifyOrResume() {
        assertThat(PptIntentRecognizer.recognize("【暂停生成PPT】"))
                .isEqualTo(PptIntent.CREATE);
    }

    @Test
    void resumeKeywordFallbackStillWorksWhenNeitherMarkerIsPresent() {
        assertThat(PptIntentRecognizer.recognize("恢复生成一下")).isEqualTo(PptIntent.RESUME);
    }
}
