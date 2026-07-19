package com.agenttrail.runtime.agentscope;

import com.agenttrail.runtime.AgentRuntime;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;

import java.util.List;

/**
 * AgentRuntime backed by AgentScope Java 2.0's ReActAgent (see ADR-0001). AgentScope owns the
 * ReAct loop and tool execution internally; AgentTrail only supplies model config and reads the
 * final answer back out. Framework types (io.agentscope.*) must not leak past this class.
 */
public class AgentScopeRuntime implements AgentRuntime {

    private final ReActAgent agent;

    public AgentScopeRuntime(String apiKey, String baseUrl, String modelName, int maxIters) {
        Model model =
                OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .formatter(new DeepSeekFormatter())
                        .stream(false)
                        .build();

        this.agent =
                ReActAgent.builder()
                        .name("AgentTrail")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .maxIters(maxIters)
                        .build();
    }

    @Override
    public String respond(String userInput) {
        Msg userMessage = Msg.builder().role(MsgRole.USER).textContent(userInput).build();
        Msg response = agent.call(List.of(userMessage)).block();
        return response.getTextContent();
    }
}
