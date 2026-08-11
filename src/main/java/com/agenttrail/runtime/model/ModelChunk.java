package com.agenttrail.runtime.model;

import java.util.Map;

public record ModelChunk(String content, ToolCallDelta toolCallDelta,
                         ModelUsage usage, String finishReason, Map<String, Object> metadata) {
    public ModelChunk(String content, ToolCallDelta toolCallDelta, ModelUsage usage, String finishReason) {
        this(content, toolCallDelta, usage, finishReason, Map.of());
    }

    public ModelChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record ToolCallDelta(String id, String name, String argumentsFragment) {
    }
}
