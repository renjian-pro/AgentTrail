package com.agenttrail.web;

/** 停止请求的结果；跨实例广播是异步的，stopped 只表示本实例是否立即停止。 */
public record StopChatResponse(String conversationId, boolean stopped) {
}
