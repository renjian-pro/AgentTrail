package com.agenttrail.loop.multimodal.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link ChatModel} 的测试替身，只支持同步 {@link #call(Prompt)}——
 * {@code com.agenttrail.loop.multimodal.ImageDescriptionService} 走的是一次性同步调用，
 * 不是 {@code stream()}，{@code ScriptedChatModel} 那一套（只支持流式）用不上这里。
 */
public class RecordingSyncChatModel implements ChatModel {

    private final Deque<ChatResponse> scriptedResponses;
    private final List<Prompt> recordedPrompts = new ArrayList<>();

    public RecordingSyncChatModel(ChatResponse... responses) {
        this.scriptedResponses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        recordedPrompts.add(prompt);
        ChatResponse next = scriptedResponses.poll();
        if (next == null) {
            throw new IllegalStateException("RecordingSyncChatModel 预设的响应已用尽");
        }
        return next;
    }

    public int callCount() {
        return recordedPrompts.size();
    }

    public List<Prompt> recordedPrompts() {
        return List.copyOf(recordedPrompts);
    }
}
