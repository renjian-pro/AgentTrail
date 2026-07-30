package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行一轮里全部的工具调用，并把结果按原始调用顺序拼回。
 *
 * <p>本类是"模型的不确定输出"和"确定性的工具执行"之间的隔离层，两类异常都在这里被吸收成
 * 普通的工具结果喂回模型，而不是抛出去中断整个循环：
 * <ul>
 *   <li>参数不是合法 JSON（流被截断、maxTokens 砍掉了后半段）→ 降级成空参数（踩坑点 #2）
 *   <li>模型幻觉出一个不存在的工具 → 把"工具不存在"作为结果返回
 * </ul>
 * 两种情况模型下一轮都能看到错误、有机会自我纠正；直接抛异常则整轮对话直接死掉。
 *
 * <p>工具名到实现的映射在构造时建成 Map，而不是每次调用都线性扫一遍工具列表——
 * 工具数量上去之后（尤其挂了 MCP 和 Skills 之后）线性查找是没必要的开销。
 */
class ToolCallExecutor {

    /** 参数缺失时的兜底值，必须是合法 JSON 空对象——工具侧统一按"没传参数"处理。 */
    private static final String EMPTY_ARGUMENTS = "{}";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, ToolCallback> toolsByName;

    ToolCallExecutor(List<ToolCallback> tools) {
        this.toolsByName = tools.stream().collect(Collectors.toMap(
                tool -> tool.getToolDefinition().name(),
                Function.identity(),
                (first, duplicate) -> first));
    }

    /**
     * 并发执行本轮全部工具调用，但结果按模型请求的原始顺序回填。
     *
     * <p>用 {@code flatMapSequential} 而不是 {@code flatMap}：两者都并发订阅，
     * 但前者按**源顺序**输出、后者按**完成顺序**输出。这里必须是源顺序——OpenAI 形状的协议
     * 要求 tool 响应与 tool_call 一一对应，顺序错了模型侧会错位关联。
     *
     * <p>工具调用是阻塞式的（{@link ToolCallback#call} 是同步接口），所以放到
     * {@code boundedElastic} 上跑，避免占住事件循环线程。
     * TODO(#10)：工具池要和流式聚合池隔离开，现在共用 boundedElastic，高并发下会互相阻塞（踩坑点 #62）。
     *
     * @param toolCalls 已重组完成的工具调用
     * @param sink      事件流，用于实时推送 ToolStart / ToolEnd
     * @return 与 {@code toolCalls} 一一对应、顺序一致的工具响应
     */
    List<ToolResponse> execute(List<ToolCall> toolCalls, Sinks.Many<AgentStreamEvent> sink) {
        return Flux.fromIterable(toolCalls)
                .flatMapSequential(toolCall -> Mono
                        .fromCallable(() -> executeOne(toolCall, sink))
                        .subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block();
    }

    private ToolResponse executeOne(ToolCall toolCall, Sinks.Many<AgentStreamEvent> sink) {
        String arguments = sanitizeArguments(toolCall);
        sink.tryEmitNext(new AgentStreamEvent.ToolStart(toolCall.name(), toolCall.id(), arguments));

        ToolCallback tool = toolsByName.get(toolCall.name());
        String result = (tool == null)
                ? errorPayload("unknown tool: " + toolCall.name())
                : tool.call(arguments);

        sink.tryEmitNext(new AgentStreamEvent.ToolEnd(toolCall.name(), toolCall.id(), result));
        return new ToolResponse(toolCall.id(), toolCall.name(), result);
    }

    /**
     * 整轮结束后统一校验一次参数 JSON 的合法性——这是分片重组之后唯一有意义的校验时机，
     * 拼接过程中的半截分片必然不合法，中途校验只会产生假警报（见 {@link ToolCallAccumulator}）。
     */
    private String sanitizeArguments(ToolCall toolCall) {
        String arguments = toolCall.arguments();
        if (arguments == null || arguments.isBlank()) {
            return EMPTY_ARGUMENTS;
        }
        try {
            JSON.readTree(arguments);
            return arguments;
        } catch (Exception malformed) {
            return EMPTY_ARGUMENTS;
        }
    }

    /** 工具层面的错误照样以"工具结果"的形式喂回模型，让模型自己决定改参数重试还是换路子。 */
    private String errorPayload(String message) {
        return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
    }
}
