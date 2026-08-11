package com.agenttrail.runtime.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DefaultToolResolver implements ToolResolver {
    private final Map<String, ToolDefinition> registry;

    public DefaultToolResolver(List<ToolDefinition> tools) {
        this.registry = tools.stream().collect(Collectors.toMap(
                ToolDefinition::name,
                Function.identity(),
                (first, duplicate) -> {
                    throw new IllegalStateException("Duplicate tool registration: " + first.name());
                },
                LinkedHashMap::new));
    }

    @Override
    public List<ToolDefinition> resolve(ToolResolutionContext context) {
        return List.copyOf(registry.values());
    }
}
