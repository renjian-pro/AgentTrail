package com.agenttrail.infrastructure.llm.springai;

import com.agenttrail.runtime.model.ModelChunk;
import com.agenttrail.runtime.model.ModelGateway;
import com.agenttrail.runtime.model.ModelRequest;
import com.agenttrail.runtime.model.ModelUsage;
import com.agenttrail.runtime.tool.ToolDefinition;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SpringAiModelGateway implements ModelGateway {
    private final ChatModel chatModel;
    private final Map<String, ToolCallback> callbacksByName;
    private final Duration ttftTimeout;
    private final Duration idleTimeout;

    public SpringAiModelGateway(ChatModel chatModel, List<ToolCallback> callbacks) {
        this(chatModel, callbacks, Duration.ofSeconds(60), Duration.ofSeconds(30));
    }

    public SpringAiModelGateway(ChatModel chatModel, List<ToolCallback> callbacks,
                                Duration ttftTimeout, Duration idleTimeout) {
        this.chatModel = chatModel;
        this.callbacksByName = callbacks.stream().collect(Collectors.toUnmodifiableMap(
                callback -> callback.getToolDefinition().name(), Function.identity(), (first, duplicate) -> first));
        this.ttftTimeout = ttftTimeout;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public Flux<ModelChunk> streamRound(ModelRequest request) {
        return chatModel.stream(new Prompt(toSpringAiMessages(request.messages()), buildOptions(request.tools())))
                .timeout(Mono.delay(ttftTimeout), chunk -> Mono.delay(idleTimeout))
                .flatMapIterable(this::toModelChunks);
    }

    private List<Message> toSpringAiMessages(List<ModelRequest.ModelMessage> messages) {
        return messages.stream().map(message -> switch (message.role()) {
            case SYSTEM -> (Message) new SystemMessage(message.content());
            case USER -> (Message) new UserMessage(message.content());
            case ASSISTANT -> {
                AssistantMessage.Builder<?> builder = AssistantMessage.builder().content(message.content());
                if (!message.metadata().isEmpty()) builder.properties(message.metadata());
                if (!message.toolCalls().isEmpty()) {
                    builder.toolCalls(message.toolCalls().stream()
                            .map(call -> new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), call.arguments()))
                            .toList());
                }
                yield (Message) builder.build();
            }
            case TOOL -> (Message) ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.toolCallId(), message.toolName(), message.content())))
                    .build();
        }).toList();
    }

    private ChatOptions buildOptions(List<ToolDefinition> tools) {
        ChatOptions defaultOptions = chatModel.getOptions();
        ChatOptions.Builder<?> builder = defaultOptions != null
                ? defaultOptions.mutate()
                : ToolCallingChatOptions.builder();
        if (builder instanceof ToolCallingChatOptions.Builder<?> toolBuilder) {
            List<ToolCallback> callbacks = tools.stream()
                    .map(ToolDefinition::name)
                    .map(callbacksByName::get)
                    .peek(callback -> {
                        if (callback == null) {
                            throw new IllegalStateException("No Spring AI callback registered for requested tool");
                        }
                    })
                    .toList();
            toolBuilder.toolCallbacks(callbacks);
        }
        return builder.build();
    }

    private List<ModelChunk> toModelChunks(ChatResponse response) {
        ModelUsage usage = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null
                : new ModelUsage(response.getMetadata().getUsage().getPromptTokens(),
                response.getMetadata().getUsage().getCompletionTokens());
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of(new ModelChunk("", null, usage, null, Map.of()));
        }

        AssistantMessage output = response.getResult().getOutput();
        String content = output.getText() == null ? "" : output.getText();
        String finishReason = response.getResult().getMetadata() == null
                ? null : response.getResult().getMetadata().getFinishReason();
        if (!output.hasToolCalls()) {
            return List.of(new ModelChunk(content, null, usage, finishReason, output.getMetadata()));
        }
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        return java.util.stream.IntStream.range(0, toolCalls.size())
                .mapToObj(index -> {
                    AssistantMessage.ToolCall call = toolCalls.get(index);
                    return new ModelChunk(index == 0 ? content : "",
                            new ModelChunk.ToolCallDelta(call.id(), call.name(), call.arguments()),
                            index == toolCalls.size() - 1 ? usage : null,
                            index == toolCalls.size() - 1 ? finishReason : null, output.getMetadata());
                }).toList();
    }
}
