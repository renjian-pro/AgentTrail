package com.agenttrail.web.dto;
import com.agenttrail.web.service.AgentLoopExecutorFactory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param conversationId   会话标识；首次请求不传时由服务端创建，后续轮次必须原样带回
 * @param modelId          可选的模型标识（issue #20），不传时由 {@link AgentLoopExecutorFactory} 落到
 *                         默认模型（{@code qwen-plus}）
 * @param webSearchEnabled 这次对话要不要挂联网搜索工具（issue #22）。不传按 false 处理——
 *                         "条件工具"的语义是关闭时工具列表里压根没有它，不是默认打开
 * @param mode             前端选中的能力模式；目前只有 {@code "analytics"} 被识别，见
 *                         {@code ChatApplicationService#send}
 */
public record AgentChatRequest(
        @NotBlank @Size(max = 20000) String message,
        @Size(max = 100) String conversationId,
        @Size(max = 100) String modelId,
        boolean webSearchEnabled,
        @Size(max = 50) String mode) {

    public AgentChatRequest(String message, String conversationId, String modelId, boolean webSearchEnabled) {
        this(message, conversationId, modelId, webSearchEnabled, null);
    }

    public AgentChatRequest(String message) {
        this(message, null, null, false, null);
    }

    public AgentChatRequest(String message, String modelId) {
        this(message, null, modelId, false, null);
    }

    public AgentChatRequest(String message, String modelId, boolean webSearchEnabled) {
        this(message, null, modelId, webSearchEnabled, null);
    }
}
