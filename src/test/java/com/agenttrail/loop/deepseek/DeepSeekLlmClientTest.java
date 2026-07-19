package com.agenttrail.loop.deepseek;

import com.agenttrail.loop.ChatMessage;
import com.agenttrail.loop.LlmResponse;
import com.agenttrail.loop.Role;
import com.agenttrail.loop.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeepSeekLlmClient client =
            new DeepSeekLlmClient("test-key", "https://api.deepseek.com", "deepseek-chat");

    @Test
    void buildsRequestBodyWithModelAndMappedMessages() throws Exception {
        List<ChatMessage> messages = List.of(
                new ChatMessage(Role.SYSTEM, "you are helpful"),
                new ChatMessage(Role.USER, "hello")
        );

        String body = client.buildRequestBody(messages, List.of());
        JsonNode root = objectMapper.readTree(body);

        assertThat(root.get("model").asText()).isEqualTo("deepseek-chat");
        assertThat(root.get("messages")).hasSize(2);
        assertThat(root.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(root.get("messages").get(0).get("content").asText()).isEqualTo("you are helpful");
        assertThat(root.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(root.get("messages").get(1).get("content").asText()).isEqualTo("hello");
        assertThat(root.has("tools")).isFalse();
    }

    @Test
    void buildsRequestBodyWithToolsWhenToolsAreProvided() throws Exception {
        List<ChatMessage> messages = List.of(new ChatMessage(Role.USER, "echo ping"));
        List<ToolSpec> tools = List.of(new ToolSpec("echo", "Echoes the input back"));

        String body = client.buildRequestBody(messages, tools);
        JsonNode root = objectMapper.readTree(body);

        assertThat(root.get("tools")).hasSize(1);
        JsonNode function = root.get("tools").get(0).get("function");
        assertThat(function.get("name").asText()).isEqualTo("echo");
        assertThat(function.get("description").asText()).isEqualTo("Echoes the input back");
    }

    @Test
    void parsesFinalAnswerFromPlainTextResponse() {
        String responseJson = """
                {
                  "choices": [
                    {
                      "message": { "role": "assistant", "content": "hello there" },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;

        LlmResponse response = client.parseResponse(responseJson);

        assertThat(response).isInstanceOf(LlmResponse.FinalAnswer.class);
        assertThat(((LlmResponse.FinalAnswer) response).text()).isEqualTo("hello there");
    }

    @Test
    void parsesToolCallWithDecodedArgumentsFromResponse() {
        String responseJson = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_123",
                            "type": "function",
                            "function": { "name": "echo", "arguments": "{\\"text\\":\\"ping\\"}" }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ]
                }
                """;

        LlmResponse response = client.parseResponse(responseJson);

        assertThat(response).isInstanceOf(LlmResponse.ToolCall.class);
        LlmResponse.ToolCall toolCall = (LlmResponse.ToolCall) response;
        assertThat(toolCall.request().toolName()).isEqualTo("echo");
        assertThat(toolCall.request().arguments()).isEqualTo(Map.of("text", "ping"));
    }
}
