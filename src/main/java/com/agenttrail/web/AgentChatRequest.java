package com.agenttrail.web;

/**
 * @param modelId 可选的模型标识（issue #20），不传时由 {@link AgentLoopExecutorFactory} 落到默认模型
 *                （{@code qwen-plus}）。V0 的 {@code AgentController} 不看这个字段，加了也不影响它。
 */
public record AgentChatRequest(String message, String modelId) {

    public AgentChatRequest(String message) {
        this(message, null);
    }
}
