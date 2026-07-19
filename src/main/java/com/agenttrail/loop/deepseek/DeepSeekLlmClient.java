package com.agenttrail.loop.deepseek;

import com.agenttrail.loop.AgentLoopException;
import com.agenttrail.loop.ChatMessage;
import com.agenttrail.loop.LlmClient;
import com.agenttrail.loop.LlmResponse;
import com.agenttrail.loop.Role;
import com.agenttrail.loop.ToolCallRequest;
import com.agenttrail.loop.ToolSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LlmClient backed by DeepSeek's OpenAI-compatible chat completions API.
 * Request/response shape: https://api-docs.deepseek.com/api/create-chat-completion
 */
public class DeepSeekLlmClient implements LlmClient {

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
