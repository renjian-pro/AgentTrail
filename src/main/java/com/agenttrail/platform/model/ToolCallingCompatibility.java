package com.agenttrail.platform.model;

/**
 * Shared qwen-plus -> deepseek-chat tool-calling fallback rule: qwen-plus has a known
 * spring-ai streaming tool_call compatibility bug, so requests that need tool calling
 * must be routed to a compatible model instead.
 */
public final class ToolCallingCompatibility {
    public static final String QWEN_PLUS = "qwen-plus";
    public static final String FALLBACK_MODEL_ID = "deepseek-chat";

    private ToolCallingCompatibility() {
    }

    public static boolean needsFallback(String requestedModelId, boolean hasTools) {
        return hasTools && QWEN_PLUS.equals(requestedModelId);
    }
}
