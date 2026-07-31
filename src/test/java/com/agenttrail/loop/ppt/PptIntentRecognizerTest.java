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
}
