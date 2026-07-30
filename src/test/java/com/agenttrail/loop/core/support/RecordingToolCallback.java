package com.agenttrail.loop.core.support;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/** Test double for {@link ToolCallback}: records every raw JSON arguments string it's called with. */
public class RecordingToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final String result;
    private final List<String> recordedArguments = new ArrayList<>();

    public RecordingToolCallback(String name, String description, String result) {
        this.definition = ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
        this.result = result;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        recordedArguments.add(toolInput);
        return result;
    }

    public List<String> recordedArguments() {
        return recordedArguments;
    }
}
