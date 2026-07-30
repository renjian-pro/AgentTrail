package com.agenttrail.loop.core.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

/** Factory helpers for building scripted {@link ChatResponse} chunks in tests. */
public final class ChatResponses {

    private ChatResponses() {
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }

    public static ChatResponse toolCall(String id, String name, String argumentsFragment) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(id, "function", name, argumentsFragment);
        AssistantMessage message = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
