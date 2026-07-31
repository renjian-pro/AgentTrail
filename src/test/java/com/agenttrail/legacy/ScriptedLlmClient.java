package com.agenttrail.legacy;

import com.agenttrail.legacy.V0.ChatMessage;
import com.agenttrail.legacy.V0.LlmClient;
import com.agenttrail.legacy.V0.LlmResponse;
import com.agenttrail.legacy.V0.ToolSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test double for LlmClient: returns pre-scripted responses in order, one per call.
 * Records every call's messages/tools so tests can assert on what the loop sent.
 */
class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> scriptedResponses;
    private final List<List<ChatMessage>> recordedCalls = new ArrayList<>();

    ScriptedLlmClient(List<LlmResponse> scriptedResponses) {
        this.scriptedResponses = new ArrayDeque<>(scriptedResponses);
    }

    @Override
    public LlmResponse call(List<ChatMessage> messages, List<ToolSpec> availableTools) {
        recordedCalls.add(List.copyOf(messages));
        if (scriptedResponses.isEmpty()) {
            throw new IllegalStateException("ScriptedLlmClient ran out of scripted responses");
        }
        return scriptedResponses.poll();
    }

    int callCount() {
        return recordedCalls.size();
    }

    List<ChatMessage> messagesAtCall(int index) {
        return recordedCalls.get(index);
    }
}
