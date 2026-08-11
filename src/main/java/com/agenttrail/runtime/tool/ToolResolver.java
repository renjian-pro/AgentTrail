package com.agenttrail.runtime.tool;

import java.util.List;

public interface ToolResolver {
    List<ToolDefinition> resolve(ToolResolutionContext context);
}
