package com.agenttrail.capability.ppt;

/** SCHEMA 状态里单张内容页要填的文字（issue #24）——对应模板第 1 张幻灯片的两个占位符。 */
public record PptContentSlideFill(String slideTitleText, String slideBodyText) {
}
