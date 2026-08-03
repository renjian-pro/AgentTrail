package com.agenttrail.web;

/** 侧栏会话列表的轻量条目；正文仅在用户选中后通过 history 接口按需加载。 */
public record ConversationSummaryResponse(String conversationId, String title, long lastActiveAtMillis) {
}
