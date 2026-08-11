package com.agenttrail.runtime.tool;

import java.util.Map;

public record ToolDefinition(String name, String description,
                             Map<String, Object> parameterSchema, RiskLevel riskLevel) {
    public ToolDefinition {
        parameterSchema = parameterSchema == null ? Map.of() : Map.copyOf(parameterSchema);
    }

    public enum RiskLevel {
        READ_ONLY,
        WRITE,
        HIGH_RISK
    }
}
