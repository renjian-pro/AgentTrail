package com.agenttrail.loop.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 覆盖大陆手机号/身份证号/银行卡号三种最常见格式，不追求商用 DLP 级别的准确率（ticket 09）。 */
class PiiMaskerTest {

    private final PiiMasker masker = PiiMasker.create();

    @Test
    void masksAMobilePhoneNumberKeepingTheFirstThreeAndLastFourDigits() {
        String masked = masker.mask("我的手机号是13812345678，方便联系");

        assertThat(masked).contains("138****5678").doesNotContain("13812345678");
    }

    @Test
    void masksAnIdCardNumberKeepingTheRegionCodeAndCheckSegment() {
        String masked = masker.mask("身份证号：110105199003072316，麻烦核实一下");

        assertThat(masked).contains("110105********2316").doesNotContain("110105199003072316");
    }

    @Test
    void masksABankCardNumberKeepingTheFirstAndLastFourDigits() {
        String masked = masker.mask("银行卡号 6222021234567890123，帮我查一下余额");

        assertThat(masked).contains("6222********0123").doesNotContain("6222021234567890123");
    }

    @Test
    void maskingIdCardFirstPreventsTheBankCardPatternFromReMatchingTheSameDigits() {
        // 18 位身份证号也满足银行卡号的 16-19 位数字这一形状，必须先按身份证号处理掉，
        // 不能让打码后的结果混着两种格式的痕迹
        String masked = masker.mask("110105199003072316");

        assertThat(masked).isEqualTo("110105********2316");
    }

    @Test
    void leavesTextWithoutAnyPiiUnchanged() {
        String text = "今天天气不错，适合出去走走";

        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    void handlesNullAndBlankInputWithoutThrowing() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void masksMultiplePiiItemsInTheSameMessage() {
        String masked = masker.mask("手机 13812345678，身份证 110105199003072316");

        assertThat(masked)
                .contains("138****5678")
                .contains("110105********2316")
                .doesNotContain("13812345678")
                .doesNotContain("110105199003072316");
    }
}
