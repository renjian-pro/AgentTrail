package com.agenttrail.loop.core;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Every round calls this directly against {@link ChatModel#stream(Prompt)} — no {@code ChatClient}/Advisor
 * chain in the middle (see ADR-0002). The tool list is passed in per round rather than fixed at construction
 * time, so ToolSearch can grow it round over round without rebuilding this class.
 */
class LlmInvoker {

    private final ChatModel chatModel;

    LlmInvoker(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    Flux<ChatResponse> streamRound(List<Message> messages, List<ToolCallback> tools) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .build();
        Prompt prompt = new Prompt(messages, options);
        return chatModel.stream(prompt);
    }
}
