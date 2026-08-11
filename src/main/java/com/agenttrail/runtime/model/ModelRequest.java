package com.agenttrail.runtime.model;

import com.agenttrail.runtime.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

public record ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools) {
    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public record ModelMessage(Role role, String content, String toolCallId, String toolName,
                               Map<String, Object> metadata, List<ToolCall> toolCalls) {
        public ModelMessage(Role role, String content) {
            this(role, content, null, null, Map.of(), List.of());
        }

        public ModelMessage(Role role, String content, String toolCallId, String toolName) {
            this(role, content, toolCallId, toolName, Map.of(), List.of());
        }

        public ModelMessage(Role role, String content, String toolCallId, String toolName,
                            Map<String, Object> metadata) {
            this(role, content, toolCallId, toolName, metadata, List.of());
        }

        public ModelMessage {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public enum Role {
            SYSTEM,
            USER,
            ASSISTANT,
            TOOL
        }
    }

    public record ToolCall(String id, String type, String name, String arguments) { }
}
