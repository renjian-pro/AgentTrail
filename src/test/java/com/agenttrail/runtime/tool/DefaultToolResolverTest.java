package com.agenttrail.runtime.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultToolResolverTest {
    @Test
    void resolvesRegisteredToolsInRegistrationOrder() {
        ToolDefinition first = new ToolDefinition("first", "", Map.of(), ToolDefinition.RiskLevel.READ_ONLY);
        ToolDefinition second = new ToolDefinition("second", "", Map.of(), ToolDefinition.RiskLevel.WRITE);

        assertEquals(List.of(first, second), new DefaultToolResolver(List.of(first, second))
                .resolve(new ToolResolutionContext("conversation", 1)));
    }

    @Test
    void rejectsDuplicateNames() {
        ToolDefinition first = new ToolDefinition("same", "first", Map.of(), ToolDefinition.RiskLevel.READ_ONLY);
        ToolDefinition second = new ToolDefinition("same", "second", Map.of(), ToolDefinition.RiskLevel.WRITE);

        assertThrows(IllegalStateException.class, () -> new DefaultToolResolver(List.of(first, second)));
    }
}
