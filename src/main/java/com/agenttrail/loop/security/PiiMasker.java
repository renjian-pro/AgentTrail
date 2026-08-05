package com.agenttrail.loop.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户输入进 LLM 前的 PII 打码：覆盖大陆手机号/身份证号/银行卡号三种最常见格式。
 *
 * <p>参考 {@code houbb/sensitive} 的规则集设计思路，但没有引入这个依赖本身——它是否已经在
 * {@code pom.xml} 里、规则集是否覆盖中文场景需要额外验证，而这三种格式的正则本身并不复杂，
 * 直接手写规则表比引入一整个脱敏框架依赖更可控（见 ticket 09 的取舍说明）。这一票不追求商用
 * DLP 产品级别的准确率，只覆盖这三种最常见格式。
 *
 * <p>顺序很关键：身份证号（18 位，结构固定）先匹配，再匹配手机号（11 位），最后用银行卡号
 * （16-19 位泛匹配）扫剩下的数字串——反过来银行卡号的宽正则会先把身份证号、手机号吃掉，
 * 打出不符合预期的码。
 */
public final class PiiMasker {

    // 18 位身份证号：地区码(6) + 出生年月日(8) + 顺序码(3) + 校验位(1，可能是 X/x)
    private static final Pattern ID_CARD = Pattern.compile(
            "\\b([1-9]\\d{5})(\\d{8})(\\d{3}[\\dXx])\\b");
    // 大陆手机号：1 + [3-9] + 9 位数字
    private static final Pattern PHONE = Pattern.compile("\\b(1[3-9]\\d)(\\d{4})(\\d{4})\\b");
    // 银行卡号：16-19 位连续数字（先跑完身份证号/手机号打码，剩下的长数字串按银行卡号处理）
    private static final Pattern BANK_CARD = Pattern.compile("\\b(\\d{4})\\d{8,11}(\\d{4})\\b");

    private PiiMasker() {
    }

    public static PiiMasker create() {
        return new PiiMasker();
    }

    public String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = maskIdCard(text);
        masked = maskPhone(masked);
        masked = maskBankCard(masked);
        return masked;
    }

    private String maskIdCard(String text) {
        Matcher matcher = ID_CARD.matcher(text);
        return matcher.replaceAll(result -> result.group(1) + "********" + result.group(3));
    }

    private String maskPhone(String text) {
        Matcher matcher = PHONE.matcher(text);
        return matcher.replaceAll(result -> result.group(1) + "****" + result.group(3));
    }

    private String maskBankCard(String text) {
        Matcher matcher = BANK_CARD.matcher(text);
        return matcher.replaceAll(result -> result.group(1) + "********" + result.group(2));
    }
}
