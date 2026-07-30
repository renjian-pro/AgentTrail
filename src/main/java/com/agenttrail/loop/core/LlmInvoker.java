package com.agenttrail.loop.core;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型调用的唯一出口：每轮直接打到 {@link ChatModel#stream(Prompt)}，
 * 中间不经过 {@code ChatClient}/Advisor 链（ADR-0002）。
 *
 * <p>这么做的收益是绕开了"框架自动执行工具"这一层——那个开关只存在于 ChatClient 上，
 * 而且在 Spring AI 各版本之间反复变动；直接调 ChatModel 之后，工具调用天然全在自己手里，
 * 也不用关心版本差异。
 *
 * <p>工具列表是**每轮作为参数传入**的，不是构造时固定死——这是 ToolSearch 延迟工具发现
 * 能生效的前提：模型这一轮通过检索发现的工具，下一轮才会出现在请求里。
 */
class LlmInvoker {

    private final ChatModel chatModel;

    LlmInvoker(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    Flux<ChatResponse> streamRound(List<Message> messages, List<ToolCallback> tools) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .build();
        return chatModel.stream(new Prompt(messages, options));
    }
}
