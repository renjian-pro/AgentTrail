package com.agenttrail.loop.core;

import com.agenttrail.infrastructure.llm.springai.SpringAiModelGateway;
import com.agenttrail.runtime.model.ModelChunk;
import com.agenttrail.runtime.model.ModelGateway;
import com.agenttrail.runtime.model.ModelRequest;
import com.agenttrail.runtime.tool.ToolDefinition;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The legacy Spring AI response facade used by AgentLoopExecutor. */
class LlmInvoker {
    private static final Duration DEFAULT_TTFT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private final ModelGateway modelGateway;
    private final ChatModel chatModel;
    private final Duration ttftTimeout;
    private final Duration idleTimeout;

    LlmInvoker(ChatModel chatModel) {
        this(chatModel, DEFAULT_TTFT_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    }

    LlmInvoker(ChatModel chatModel, Duration ttftTimeout, Duration idleTimeout) {
        this.modelGateway = null;
        this.chatModel = chatModel;
        this.ttftTimeout = ttftTimeout;
        this.idleTimeout = idleTimeout;
    }

    LlmInvoker(ChatModel chatModel, List<ToolCallback> tools) {
        this.modelGateway = new SpringAiModelGateway(chatModel, tools, DEFAULT_TTFT_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
        this.chatModel = chatModel;
        this.ttftTimeout = DEFAULT_TTFT_TIMEOUT;
        this.idleTimeout = DEFAULT_IDLE_TIMEOUT;
    }

    LlmInvoker(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
        this.chatModel = null;
        this.ttftTimeout = DEFAULT_TTFT_TIMEOUT;
        this.idleTimeout = DEFAULT_IDLE_TIMEOUT;
    }

    Flux<ChatResponse> streamRound(List<Message> messages, List<ToolCallback> tools) {
        ModelRequest request = toModelRequest(messages, tools);
        return Flux.from(streamModelRound(request, tools)).map(LlmInvoker::toChatResponse);
    }

    reactor.core.publisher.Flux<ModelChunk> streamModelRound(ModelRequest request, List<ToolCallback> tools) {
        ModelGateway gateway = chatModel == null
                ? modelGateway : new SpringAiModelGateway(chatModel, tools, ttftTimeout, idleTimeout);
        return Flux.from(gateway.streamRound(request));
    }

    static ModelRequest toModelRequest(List<Message> messages, List<ToolCallback> tools) {
        return new ModelRequest(
                messages.stream().flatMap(message -> toModelMessages(message).stream()).toList(),
                tools.stream().map(LlmInvoker::toToolDefinition).toList());
    }

    private static List<ModelRequest.ModelMessage> toModelMessages(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            return toolResponseMessage.getResponses().stream()
                    .map(response -> new ModelRequest.ModelMessage(ModelRequest.ModelMessage.Role.TOOL,
                            response.responseData(), response.id(), response.name(), Map.of()))
                    .toList();
        }
        ModelRequest.ModelMessage.Role role = switch (message.getMessageType()) {
            case SYSTEM -> ModelRequest.ModelMessage.Role.SYSTEM;
            case USER -> ModelRequest.ModelMessage.Role.USER;
            case ASSISTANT -> ModelRequest.ModelMessage.Role.ASSISTANT;
            case TOOL -> ModelRequest.ModelMessage.Role.TOOL;
        };
        List<ModelRequest.ToolCall> toolCalls = message instanceof AssistantMessage assistant
                ? assistant.getToolCalls().stream()
                .map(call -> new ModelRequest.ToolCall(call.id(), call.type(), call.name(), call.arguments()))
                .toList()
                : List.of();
        return List.of(new ModelRequest.ModelMessage(role, message.getText() == null ? "" : message.getText(),
                null, null, message.getMetadata(), toolCalls));
    }

    private static ToolDefinition toToolDefinition(ToolCallback callback) {
        return new ToolDefinition(callback.getToolDefinition().name(),
                callback.getToolDefinition().description(), Map.of(), ToolDefinition.RiskLevel.READ_ONLY);
    }

    static ChatResponse toChatResponse(ModelChunk chunk) {
        AssistantMessage.Builder<?> builder = AssistantMessage.builder().content(chunk.content());
        if (!chunk.metadata().isEmpty()) builder.properties(chunk.metadata());
        if (chunk.toolCallDelta() != null) {
            ModelChunk.ToolCallDelta call = chunk.toolCallDelta();
            builder.toolCalls(List.of(new AssistantMessage.ToolCall(call.id(), "function", call.name(),
                    call.argumentsFragment())));
        }
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(chunk.usage() == null ? null : new DefaultUsage(chunk.usage().promptTokens(),
                        chunk.usage().completionTokens()))
                .build();
        return new ChatResponse(List.of(new Generation(builder.build())), metadata);
    }
}
