package com.agenttrail.runtime.agent;

import com.agenttrail.platform.identity.Principal;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.AgentResult;
import com.agenttrail.runtime.api.AgentRuntimePort;

import java.util.Map;

public final class SubAgentRunner {
    private final AgentRegistry registry;
    private final AgentRuntimePort runtime;

    public SubAgentRunner(AgentRegistry registry, AgentRuntimePort runtime) {
        this.registry = registry;
        this.runtime = runtime;
    }

    public AgentResult run(String agentId, String message, Principal principal, int depth,
                           long parentBudgetTokens, Map<String, Object> toolParams) {
        AgentDefinition agent = registry.find(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentId));
        if (depth >= agent.policy().maxNestingDepth()) {
            throw new IllegalStateException("Agent nesting depth exceeded for " + agentId);
        }
        long childBudget = agent.policy().maxTokens() <= 0 ? parentBudgetTokens
                : Math.min(parentBudgetTokens <= 0 ? agent.policy().maxTokens() : parentBudgetTokens,
                agent.policy().maxTokens());
        Map<String, Object> scopedTools = toolParams == null ? Map.of() : toolParams.entrySet().stream()
                .filter(entry -> agent.policy().allowedTools().isEmpty()
                        || agent.policy().allowedTools().contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return runtime.call(new AgentRequest(ConversationId.newId(), principal, message, scopedTools, null,
                new AgentRequest.Budget(childBudget <= 0 ? null : childBudget, agent.policy().timeout())));
    }
}
