package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.tools.TodoWriteTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
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

    /**
     * 工具执行专用的调度器，和 Reactor 默认的全局 {@code Schedulers.boundedElastic()} 是两个
     * 独立实例（issue #10 / 踩坑点 #62）：默认的那个是整个 JVM 里所有"随手 subscribeOn 一下"的
     * 阻塞代码共用的池子，高并发下工具执行会和应用里其它任何用到默认池的地方互相排队阻塞。
     * 单独开一个命名池，容量和默认值保持一致（够用且和默认行为对齐），只是不再共享。
     */
    private static final Scheduler TOOL_EXECUTION_SCHEDULER = Schedulers.newBoundedElastic(
            Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
            Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
            "agent-tool-exec");

    private final Map<String, ToolCallback> toolsByName;

    ToolCallExecutor(List<ToolCallback> tools) {
        this.toolsByName = tools.stream().collect(Collectors.toMap(
                tool -> tool.getToolDefinition().name(),
                Function.identity(),
                (first, duplicate) -> first));
    }

    List<ToolResponse> execute(List<ToolCall> toolCalls, Sinks.Many<AgentStreamEvent> sink,
                               ToolParamInjector paramInjector) {
        return execute(toolCalls, sink, paramInjector, null, null);
    }

    /**
     * @param sessionScopedTool 构造时的固定工具表里找不到、但这次对话请求专属的工具（比如
     *                          ToolSearch 的检索元工具本身，每个会话各有一个独立实例）；
     *                          传 null 等价于不存在这类工具
     */
    List<ToolResponse> execute(List<ToolCall> toolCalls, Sinks.Many<AgentStreamEvent> sink,
                               ToolParamInjector paramInjector, ToolCallback sessionScopedTool) {
        return execute(toolCalls, sink, paramInjector, sessionScopedTool, null);
    }

    /**
     * 并发执行本轮全部工具调用，但结果按模型请求的原始顺序回填。
     *
     * <p>用 {@code flatMapSequential} 而不是 {@code flatMap}：两者都并发订阅，
     * 但前者按**源顺序**输出、后者按**完成顺序**输出。这里必须是源顺序——OpenAI 形状的协议
     * 要求 tool 响应与 tool_call 一一对应，顺序错了模型侧会错位关联。
     *
     * <p>工具调用是阻塞式的（{@link ToolCallback#call} 是同步接口），所以放到独立的
     * {@link #TOOL_EXECUTION_SCHEDULER} 上跑，避免占住事件循环线程，也避免和默认调度器上
     * 的其它工作互相阻塞。
     *
     * @param toolCalls    已重组完成的工具调用
     * @param sink         事件流，用于实时推送 ToolStart / ToolEnd
     * @param mdcSnapshot  发起本次对话请求的线程的 MDC 快照；跳到 {@link #TOOL_EXECUTION_SCHEDULER}
     *                     的线程之前先还原一次，工具执行期间的日志才带得上 conversationId 这类字段
     *                     （见 {@link MdcPropagation}）。传 null 等价于不做任何还原
     * @return 与 {@code toolCalls} 一一对应、顺序一致的工具响应
     */
    List<ToolResponse> execute(List<ToolCall> toolCalls, Sinks.Many<AgentStreamEvent> sink,
                               ToolParamInjector paramInjector, ToolCallback sessionScopedTool,
                               Map<String, String> mdcSnapshot) {
        return Flux.fromIterable(toolCalls)
                .flatMapSequential(toolCall -> Mono
                        .fromCallable(() -> MdcPropagation.call(mdcSnapshot,
                                () -> executeOne(toolCall, sink, paramInjector, sessionScopedTool)))
                        .subscribeOn(TOOL_EXECUTION_SCHEDULER))
                .collectList()
                .block();
    }

    private ToolResponse executeOne(ToolCall toolCall, Sinks.Many<AgentStreamEvent> sink,
                                    ToolParamInjector paramInjector, ToolCallback sessionScopedTool) {
        ToolCallback tool = resolve(toolCall.name(), sessionScopedTool);
        if (tool == null) {
            // 模型幻觉出的工具：连参数都不必处理，直接把错误当结果喂回去
            EventSinks.emit(sink, new AgentStreamEvent.ToolStart(toolCall.name(), toolCall.id(), toolCall.arguments()));
            String result = errorPayload("unknown tool: " + toolCall.name());
            EventSinks.emit(sink, new AgentStreamEvent.ToolEnd(toolCall.name(), toolCall.id(), result));
            return new ToolResponse(toolCall.id(), toolCall.name(), result);
        }

        // 先兜底参数合法性，再注入系统级参数——注入依赖参数是可解析的 JSON 对象
        String arguments = paramInjector.inject(sanitizeArguments(toolCall), tool.getToolDefinition());
        EventSinks.emit(sink, new AgentStreamEvent.ToolStart(toolCall.name(), toolCall.id(), arguments));

        String result = tool.call(arguments);

        EventSinks.emit(sink, new AgentStreamEvent.ToolEnd(toolCall.name(), toolCall.id(), result));
        emitTodoProgressIfApplicable(toolCall.name(), arguments, sink);
        return new ToolResponse(toolCall.id(), toolCall.name(), result);
    }

    /**
     * {@code TodoWrite} 是唯一需要在工具执行之外额外广播一个事件的工具：进度快照必须来自
     * 重新解析的原始参数，不能依赖 {@code result}（工具的返回文本）——两者各自独立解析同一份
     * JSON，这样 {@link TodoWriteTool} 内部实现的任何改动都不会悄悄影响前端看到的进度。
     * 解析失败（结构不合法）就静默跳过，不影响这一轮工具调用本身的结果。
     */
    private void emitTodoProgressIfApplicable(String toolName, String arguments, Sinks.Many<AgentStreamEvent> sink) {
        if (!TodoWriteTool.TOOL_NAME.equals(toolName)) {
            return;
        }
        TodoWriteTool.parseSnapshot(arguments)
                .ifPresent(items -> EventSinks.emit(sink, new AgentStreamEvent.TodoProgress(items)));
    }

    /** 先查固定表，查不到再看是不是这次会话专属的那一个（按名字比对，不假设调用方传对了）。 */
    private ToolCallback resolve(String name, ToolCallback sessionScopedTool) {
        ToolCallback tool = toolsByName.get(name);
        if (tool != null) {
            return tool;
        }
        if (sessionScopedTool != null && sessionScopedTool.getToolDefinition().name().equals(name)) {
            return sessionScopedTool;
        }
        return null;
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
