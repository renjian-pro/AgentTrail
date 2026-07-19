package com.agenttrail.loop;

public sealed interface LlmResponse {

    record FinalAnswer(String text) implements LlmResponse {
    }

    record ToolCall(ToolCallRequest request) implements LlmResponse {
    }
}
