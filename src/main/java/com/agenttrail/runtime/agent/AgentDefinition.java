package com.agenttrail.runtime.agent;

import com.agenttrail.runtime.RuntimeProfile;
import com.agenttrail.runtime.tool.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public record AgentDefinition(String id, String description, RuntimeProfile profile,
                              List<ToolDefinition> tools, InputContract input,
                              OutputContract output, AgentPolicy policy) {
    public AgentDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("agent id is required");
        tools = tools == null ? List.of() : List.copyOf(tools);
        input = input == null ? new InputContract(Set.of()) : input;
        output = output == null ? new OutputContract("text/plain") : output;
        policy = policy == null ? AgentPolicy.defaults() : policy;
    }

    public record InputContract(Set<String> keywords) {
        public InputContract { keywords = keywords == null ? Set.of() : Set.copyOf(keywords); }
        public boolean matches(String message) {
            String normalized = message == null ? "" : message.toLowerCase();
            return keywords.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase()));
        }
    }

    public record OutputContract(String mediaType) { }

    public record AgentPolicy(long maxTokens, Duration timeout, int maxNestingDepth, Set<String> allowedTools) {
        public AgentPolicy {
            if (maxNestingDepth < 0) throw new IllegalArgumentException("maxNestingDepth must be non-negative");
            timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        }
        public static AgentPolicy defaults() { return new AgentPolicy(0, Duration.ofMinutes(2), 3, Set.of()); }
    }
}
