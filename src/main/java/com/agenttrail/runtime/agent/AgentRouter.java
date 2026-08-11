package com.agenttrail.runtime.agent;

import java.util.List;
import java.util.Optional;

public final class AgentRouter {
    @FunctionalInterface
    public interface ModelFallback {
        Optional<String> choose(String message, List<AgentDefinition> candidates);
    }

    public record Route(String agentId, Source source) {
        public enum Source { RULE, MODEL }
    }

    private final AgentRegistry registry;
    private final ModelFallback fallback;

    public AgentRouter(AgentRegistry registry, ModelFallback fallback) {
        this.registry = registry;
        this.fallback = fallback;
    }

    public Optional<Route> route(String message) {
        Optional<AgentDefinition> ruleMatch = registry.all().stream()
                .filter(agent -> agent.input().matches(message)).findFirst();
        if (ruleMatch.isPresent()) return Optional.of(new Route(ruleMatch.get().id(), Route.Source.RULE));
        if (fallback == null) return Optional.empty();
        return fallback.choose(message, registry.all()).map(id -> new Route(id, Route.Source.MODEL));
    }
}
