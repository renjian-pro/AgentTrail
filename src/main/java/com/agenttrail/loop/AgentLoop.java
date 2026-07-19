package com.agenttrail.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AgentLoop {

    private final LlmClient llmClient;
    private final Map<String, Tool> tools;
    private final List<ToolSpec> toolSpecs;
    private final int maxIterations;

    public AgentLoop(LlmClient llmClient, List<Tool> tools, int maxIterations) {
        this.llmClient = llmClient;
        this.tools = tools.stream().collect(Collectors.toMap(t -> t.spec().name(), t -> t));
        this.toolSpecs = tools.stream().map(Tool::spec).toList();
        this.maxIterations = maxIterations;
    }

    public String run(String userInput) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(Role.USER, userInput));

        for (int i = 0; i < maxIterations; i++) {
            LlmResponse response = llmClient.call(List.copyOf(messages), toolSpecs);

            if (response instanceof LlmResponse.FinalAnswer finalAnswer) {
                return finalAnswer.text();
            }

            if (response instanceof LlmResponse.ToolCall toolCall) {
                String toolName = toolCall.request().toolName();
                Tool tool = tools.get(toolName);
                if (tool == null) {
                    throw new AgentLoopException("Unknown tool requested: " + toolName);
                }
                String result = tool.execute(toolCall.request().arguments());
                messages.add(new ChatMessage(Role.ASSISTANT, "tool_call:" + toolName));
                messages.add(new ChatMessage(Role.TOOL, result));
            }
        }

        throw new AgentLoopException("Exceeded max iterations (" + maxIterations + ") without a final answer");
    }
}
