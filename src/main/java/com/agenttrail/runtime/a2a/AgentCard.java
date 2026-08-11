package com.agenttrail.runtime.a2a;

import java.util.List;

public record AgentCard(String id, String name, String description, List<String> capabilities) {
    public AgentCard { capabilities = capabilities == null ? List.of() : List.copyOf(capabilities); }
}
