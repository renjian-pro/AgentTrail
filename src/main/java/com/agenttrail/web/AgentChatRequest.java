package com.agenttrail.web;

/**
 * @param modelId          可选的模型标识（issue #20），不传时由 {@link AgentLoopExecutorFactory} 落到
 *                         默认模型（{@code qwen-plus}）。V0 的 {@code AgentController} 不看这个字段，
 *                         加了也不影响它。
 * @param webSearchEnabled 这次对话要不要挂联网搜索工具（issue #22）。不传按 false 处理——
 *                         "条件工具"的语义是关闭时工具列表里压根没有它，不是默认打开
 */
public record AgentChatRequest(String message, String modelId, boolean webSearchEnabled) {

    public AgentChatRequest(String message) {
        this(message, null, false);
    }

    public AgentChatRequest(String message, String modelId) {
        this(message, modelId, false);
    }
}
