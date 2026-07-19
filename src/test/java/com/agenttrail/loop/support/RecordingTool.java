package com.agenttrail.loop.support;

import com.agenttrail.loop.Tool;
import com.agenttrail.loop.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test double for Tool: records every call's arguments and returns a fixed result string.
 */
public class RecordingTool implements Tool {

    private final ToolSpec spec;
    private final String result;
    private final List<Map<String, Object>> executedArguments = new ArrayList<>();

    public RecordingTool(String name, String description, String result) {
        this.spec = new ToolSpec(name, description);
        this.result = result;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        executedArguments.add(arguments);
        return result;
    }

    public List<Map<String, Object>> executedArguments() {
        return executedArguments;
    }
}
