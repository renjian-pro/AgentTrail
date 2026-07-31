package com.agenttrail.loop.core;

import org.springframework.ai.chat.messages.Message;
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

/**
 * 模型调用的唯一出口：每轮直接打到 {@link ChatModel#stream(Prompt)}，不经过
 * {@code ChatClient}/Advisor 链（ADR-0002），工具执行全在自己手里。
 *
 * <p>工具列表按轮传入而非构造时固定——为 ToolSearch 延迟工具发现留出空间。
 *
 * <h2>分阶段超时（issue #10）</h2>
 * <p>思考模型的首 token 延迟（等模型"想清楚"）和后续 token 间隔（纯输出速度）是两种性质不同的
 * 等待，用同一个超时值要么首 token 还没等到就误杀，要么输出卡住了迟迟不触发。这里用 Reactor
 * 的通用 {@code timeout(firstTimeout, nextTimeoutFactory)} 重载分开设：{@link #ttftTimeout} 管
 * "订阅到第一个 chunk"这段窗口，{@link #idleTimeout} 管"每个 chunk 之后到下一个 chunk"这段窗口，
 * 每次收到新 chunk 都重新起一个新的 idle 窗口。
 *
 * <p>超时触发后 Reactor 的 {@code timeout} 操作符会真正取消上游订阅（不只是不再往下游转发），
 * 详见 {@code LlmInvokerTest} 里用 {@code doOnCancel} 探针做的验证——不能只信操作符文档，
 * 这条链路的取消语义必须有测试锁住。
 */
class LlmInvoker {

    /** 思考模型"想清楚"再开口可能要一段时间，给得比 idle 超时宽松很多。 */
    private static final Duration DEFAULT_TTFT_TIMEOUT = Duration.ofSeconds(60);

    /** 已经开始吐字之后，纯输出卡顿超过这个值就判定为连接/模型侧异常。 */
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private final ChatModel chatModel;
    private final Duration ttftTimeout;
    private final Duration idleTimeout;

    LlmInvoker(ChatModel chatModel) {
        this(chatModel, DEFAULT_TTFT_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    }

    LlmInvoker(ChatModel chatModel, Duration ttftTimeout, Duration idleTimeout) {
        this.chatModel = chatModel;
        this.ttftTimeout = ttftTimeout;
        this.idleTimeout = idleTimeout;
    }

    Flux<ChatResponse> streamRound(List<Message> messages, List<ToolCallback> tools) {
        return chatModel.stream(new Prompt(messages, buildOptions(tools)))
                .timeout(Mono.delay(ttftTimeout), chunk -> Mono.delay(idleTimeout));
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
