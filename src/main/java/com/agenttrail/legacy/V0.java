package com.agenttrail.legacy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * V0 —— 项目最早期的两条探索性路径，收敛进这一个文件里保留（不删除，不再演进），仅供对照参考。
 *
 * <p>两条独立路径：
 * <ul>
 *   <li>{@link AgentLoop} + {@link LlmClient} + {@link DeepSeekLlmClient}：最初手写的一个极简
 *       ReAct 循环原型（无流式、无工具并发、无上下文管理），验证"手写一个 loop 大概是什么形状"。
 *   <li>{@link AgentScopeRuntime}：基于 AgentScope Java 2.0 框架的实现，验证"直接套用现成框架"
 *       这条路（见 {@code docs/adr/0001-runtime-scope-and-stack.md}）。
 * </ul>
 *
 * <p>两条路径最终都被 {@code com.agenttrail.loop.core.AgentLoopExecutor}（V1，手写 ReAct 引擎，
 * 直调 Spring AI {@code ChatModel}，见 {@code docs/adr/0002-hand-rolled-loop-as-v1-mainline.md}）
 * 取代——V1 才是现在真正开发、已经接了 HTTP 入口（见 {@code web.AgentLoopController}）、后续要叠
 * Capability Pack 的主线。这份代码之所以还留着、还收敛成一个文件而不是直接删掉，是为了保留
 * "决策是怎么一步步演进过来的"这段历史：面试被问"为什么最终选择手写"时，这就是对照素材。
 *
 * <p>收敛成一个文件是有意为之：这些类互相之间只在这一个"历史存档"的语境里有意义，散落在多个
 * 包里反而会在浏览 {@code loop.core.*}（V1 的真正实现）时造成干扰。
 */
public final class V0 {

    private V0() {
    }

    // ==================== 极简 loop 原型：AgentLoop + LlmClient ====================

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public record ChatMessage(Role role, String content) {
    }

    public record ToolSpec(String name, String description) {
    }

    public record ToolCallRequest(String toolName, Map<String, Object> arguments) {
    }

    public sealed interface LlmResponse {

        record FinalAnswer(String text) implements LlmResponse {
        }

        record ToolCall(ToolCallRequest request) implements LlmResponse {
        }
    }

    public interface Tool {

        ToolSpec spec();

        String execute(Map<String, Object> arguments);
    }

    public interface LlmClient {

        LlmResponse call(List<ChatMessage> messages, List<ToolSpec> availableTools);
    }

    public static class AgentLoopException extends RuntimeException {

        public AgentLoopException(String message) {
            super(message);
        }
    }

    public static class AgentLoop {

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

    /**
     * LlmClient backed by DeepSeek's OpenAI-compatible chat completions API.
     * Request/response shape: https://api-docs.deepseek.com/api/create-chat-completion
     */
    public static class DeepSeekLlmClient implements LlmClient {

        private final HttpClient httpClient;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final String apiKey;
        private final String baseUrl;
        private final String model;

        public DeepSeekLlmClient(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        String buildRequestBody(List<ChatMessage> messages, List<ToolSpec> tools) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);

            ArrayNode messagesNode = root.putArray("messages");
            for (ChatMessage message : messages) {
                ObjectNode messageNode = messagesNode.addObject();
                messageNode.put("role", toApiRole(message.role()));
                messageNode.put("content", message.content());
            }

            if (!tools.isEmpty()) {
                ArrayNode toolsNode = root.putArray("tools");
                for (ToolSpec tool : tools) {
                    ObjectNode toolNode = toolsNode.addObject();
                    toolNode.put("type", "function");
                    ObjectNode functionNode = toolNode.putObject("function");
                    functionNode.put("name", tool.name());
                    functionNode.put("description", tool.description());
                    ObjectNode parametersNode = functionNode.putObject("parameters");
                    parametersNode.put("type", "object");
                    parametersNode.putObject("properties");
                }
            }

            return root.toString();
        }

        LlmResponse parseResponse(String responseJson) {
            try {
                JsonNode root = objectMapper.readTree(responseJson);
                JsonNode message = root.at("/choices/0/message");
                JsonNode toolCalls = message.get("tool_calls");

                if (toolCalls != null && !toolCalls.isEmpty()) {
                    JsonNode firstCall = toolCalls.get(0);
                    JsonNode function = firstCall.get("function");
                    String toolName = function.get("name").asText();
                    String argumentsJson = function.get("arguments").asText();
                    Map<String, Object> arguments =
                            objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
                    return new LlmResponse.ToolCall(new ToolCallRequest(toolName, arguments));
                }

                String content = message.path("content").asText("");
                return new LlmResponse.FinalAnswer(content);
            } catch (IOException e) {
                throw new AgentLoopException("Failed to parse DeepSeek response: " + e.getMessage());
            }
        }

        @Override
        public LlmResponse call(List<ChatMessage> messages, List<ToolSpec> availableTools) {
            String requestBody = buildRequestBody(messages, availableTools);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new AgentLoopException(
                            "DeepSeek API returned status " + response.statusCode() + ": " + response.body());
                }
                return parseResponse(response.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new AgentLoopException("DeepSeek API call failed: " + e.getMessage());
            }
        }

        private static String toApiRole(Role role) {
            return switch (role) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case TOOL -> "tool";
            };
        }
    }

    // ==================== AgentScope Java 2.0 框架路径：AgentRuntime + AgentScopeRuntime ====================

    /**
     * Domain boundary for the Runtime (see CONTEXT.md). No framework types may appear
     * in this interface's signature -- implementations live in framework-named adapter
     * classes (e.g. {@link AgentScopeRuntime}).
     */
    public interface AgentRuntime {

        String respond(String userInput);
    }

    /**
     * AgentRuntime backed by AgentScope Java 2.0's ReActAgent. AgentScope owns the ReAct loop and
     * tool execution internally; AgentTrail only supplies model config and reads the final answer
     * back out. Framework types (io.agentscope.*) must not leak past this class.
     */
    public static class AgentScopeRuntime implements AgentRuntime {

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
}
