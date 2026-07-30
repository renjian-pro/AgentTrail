package com.agenttrail.loop.core.support;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test double for {@link ChatModel}: each call to {@link #stream(Prompt)} pops the next
 * scripted round (a list of chunks) and replays it as a Flux, in order. Records every
 * round's messages/tool callbacks so tests can assert on what the loop sent upstream.
 */
public class ScriptedChatModel implements ChatModel {

    private final Deque<List<ChatResponse>> scriptedRounds;
    private final List<List<Message>> recordedMessages = new ArrayList<>();
    private final List<List<String>> recordedToolNames = new ArrayList<>();

    @SafeVarargs
    public ScriptedChatModel(List<ChatResponse>... rounds) {
        this.scriptedRounds = new ArrayDeque<>(List.of(rounds));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException("ScriptedChatModel only supports stream()");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        recordedMessages.add(List.copyOf(prompt.getInstructions()));
        recordedToolNames.add(toolNamesOf(prompt));
        if (scriptedRounds.isEmpty()) {
            throw new IllegalStateException("ScriptedChatModel ran out of scripted rounds");
        }
        return Flux.fromIterable(scriptedRounds.poll());
    }

    private List<String> toolNamesOf(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            return options.getToolCallbacks().stream().map(tc -> tc.getToolDefinition().name()).toList();
        }
        return List.of();
    }

    public int roundCount() {
        return recordedMessages.size();
    }

    public List<Message> messagesAtRound(int index) {
        return recordedMessages.get(index);
    }

    public List<String> toolNamesAtRound(int index) {
        return recordedToolNames.get(index);
    }
}
