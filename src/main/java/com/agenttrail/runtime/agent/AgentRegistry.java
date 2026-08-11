package com.agenttrail.runtime.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AgentRegistry {
    private final Map<String, AgentDefinition> definitions;

    public AgentRegistry(List<AgentDefinition> definitions) {
        Map<String, AgentDefinition> index = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions == null ? List.<AgentDefinition>of() : definitions) {
            if (index.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate agent id: " + definition.id());
            }
        }
        this.definitions = Map.copyOf(index);
    }

    public Optional<AgentDefinition> find(String id) { return Optional.ofNullable(definitions.get(id)); }
    public List<AgentDefinition> all() { return List.copyOf(definitions.values()); }
}
