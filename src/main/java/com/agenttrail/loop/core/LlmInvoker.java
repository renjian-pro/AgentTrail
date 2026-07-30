package com.agenttrail.loop.core;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型调用的唯一出口：每轮直接打到 {@link ChatModel#stream(Prompt)}，不经过
 * {@code ChatClient}/Advisor 链（ADR-0002），工具执行全在自己手里。
 *
 * <p>工具列表按轮传入而非构造时固定——为 ToolSearch 延迟工具发现留出空间。
 */
class LlmInvoker {

    private final ChatModel chatModel;

    LlmInvoker(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    Flux<ChatResponse> streamRound(List<Message> messages, List<ToolCallback> tools) {
        return chatModel.stream(new Prompt(messages, buildOptions(tools)));
    }

    /**
     * mutate 自 {@link ChatModel#getOptions()}，保留厂商具体的 options 子类型（如
     * DeepSeekChatOptions）——直接 new 一个通用 {@code ToolCallingChatOptions} 会在部分厂商的
     * createRequest 里被硬转类型时 ClassCastException（#5a⑤）。
     */
    private ChatOptions buildOptions(List<ToolCallback> tools) {
        ChatOptions defaultOptions = chatModel.getOptions();
        ChatOptions.Builder<?> builder = defaultOptions != null
                ? defaultOptions.mutate()
                : ToolCallingChatOptions.builder();
        if (builder instanceof ToolCallingChatOptions.Builder<?> toolBuilder) {
            toolBuilder.toolCallbacks(tools);
        }
        return builder.build();
    }
}
