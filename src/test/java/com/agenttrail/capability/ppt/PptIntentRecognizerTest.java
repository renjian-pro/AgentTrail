package com.agenttrail.capability.ppt;

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
    void recognizesEveryResumeKeywordVariant() {
        assertThat(PptIntentRecognizer.recognize("恢复生成一下")).isEqualTo(PptIntent.RESUME);
        assertThat(PptIntentRecognizer.recognize("接着生成剩下的")).isEqualTo(PptIntent.RESUME);
        assertThat(PptIntentRecognizer.recognize("继续之前的那份")).isEqualTo(PptIntent.RESUME);
    }

    /**
     * 曾经存在的 {@code 【开始生成PPT】}/{@code 【暂停生成PPT】} 标记已随实现一起删除——前端从来
     * 没发过它们，卡片上的"继续"按钮走的是带任务号的 /resume 端点。这条用例守住删除后的行为：
     * 那两个串现在就是普通文本，不再有任何特殊含义，落到 CREATE 兜底。
     */
    @Test
    void treatsTheRemovedMarkerStringsAsOrdinaryTextWithNoSpecialMeaning() {
        assertThat(PptIntentRecognizer.recognize("【开始生成PPT】")).isEqualTo(PptIntent.CREATE);
        assertThat(PptIntentRecognizer.recognize("【暂停生成PPT】")).isEqualTo(PptIntent.CREATE);
    }
}
