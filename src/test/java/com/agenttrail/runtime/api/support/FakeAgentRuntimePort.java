package com.agenttrail.runtime.api.support;

import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentEvent;
import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.AgentResult;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRunSnapshot;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.api.ResumeCommand;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeAgentRuntimePort implements AgentRuntimePort {
    private final Map<String, List<AgentEvent>> scriptedEvents = new HashMap<>();
    private final Map<String, String> scriptedTexts = new HashMap<>();
    private final List<AgentRequest> receivedRequests = new ArrayList<>();

    public FakeAgentRuntimePort scriptEvents(String conversationId, AgentEvent... events) {
        scriptedEvents.put(conversationId, List.of(events));
        return this;
    }

    public FakeAgentRuntimePort scriptCallResult(String conversationId, String text) {
        scriptedTexts.put(conversationId, text);
        return this;
    }

    public List<AgentRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    @Override
    public AgentRunHandle start(AgentRequest request) {
        receivedRequests.add(request);
        RunId runId = RunId.of(request.conversationId().value());
        return new AgentRunHandle(runId,
                Flux.fromIterable(scriptedEvents.getOrDefault(request.conversationId().value(), List.of())));
    }

    @Override
    public AgentResult call(AgentRequest request) {
        receivedRequests.add(request);
        RunId runId = RunId.of(request.conversationId().value());
        return new AgentResult(runId, scriptedTexts.getOrDefault(request.conversationId().value(), ""));
    }

    @Override
    public AgentRunSnapshot snapshot(RunId runId) {
        return new AgentRunSnapshot(runId, ConversationId.of(runId.value()),
                AgentRunSnapshot.RunStatus.UNKNOWN, 0);
    }

    @Override
    public void cancel(RunId runId, CancellationReason reason) {
    }

    @Override
    public AgentRunHandle resume(RunId runId, ResumeCommand command) {
        return start(new AgentRequest(ConversationId.of(runId.value()), null, "", Map.of(), null,
                AgentRequest.Budget.UNBOUNDED));
    }
}
