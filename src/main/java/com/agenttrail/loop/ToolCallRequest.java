package com.agenttrail.loop;

import java.util.Map;

public record ToolCallRequest(String toolName, Map<String, Object> arguments) {
}
