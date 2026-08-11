package com.agenttrail.runtime;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/** Pure message assembly seam; persistence and model calls stay outside this module. */
public final class ContextAssembler {
    public List<Message> assemble(String systemPrompt, List<Message> history, String userMessage) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        if (history != null) {
            messages.addAll(history);
        }
        if (userMessage != null) {
            messages.add(new UserMessage(userMessage));
        }
        return List.copyOf(messages);
    }
}
