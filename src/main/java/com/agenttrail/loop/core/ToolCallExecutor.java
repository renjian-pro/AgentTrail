package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.tools.TodoWriteTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行一轮里全部的工具调用，并把结果按原始调用顺序拼回。
 *
 * <p>本类是"模型的不确定输出"和"确定性的工具执行"之间的隔离层，几类异常都在这里被吸收成
 * 普通的工具结果喂回模型，而不是抛出去中断整个循环：
 * <ul>
 *   <li>参数不是合法 JSON（流被截断、maxTokens 砍掉了后半段）→ 降级成空参数（踩坑点 #2）
 *   <li>模型幻觉出一个不存在的工具 → 把"工具不存在"作为结果返回
 *   <li>单个工具调用抛异常（MCP 超时、远端 5xx）→ {@link #executeOne} 内部已经 catch 住
 *   <li>一整轮工具调用集体卡住太久（踩坑点 #92 的审计结论：这里原来没有任何应用层超时兜底，
 *       和修复前的 6 处同步 {@code chatModel.call()} 是同一个模式）→ {@link #DEFAULT_ROUND_TIMEOUT}
 *       兜底，超时后把这一轮里每个工具调用都合成一条超时错误喂回模型
 * </ul>
 * 这几种情况模型下一轮都能看到错误、有机会自我纠正；直接抛异常则整轮对话直接死掉。
 *
 * <p>工具名到实现的映射在构造时建成 Map，而不是每次调用都线性扫一遍工具列表——
 * 工具数量上去之后（尤其挂了 MCP 和 Skills 之后）线性查找是没必要的开销。
 */
class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);

    /** 参数缺失时的兜底值，必须是合法 JSON 空对象——工具侧统一按"没传参数"处理。 */
    private static final String EMPTY_ARGUMENTS = "{}";

    /**
     * 一轮里全部工具调用（并发跑）合在一起的硬上限——不是"单个工具调用"的超时，那个由每个
     * 工具自己的实现负责（Bash 有 {@code process.waitFor}，Tavily/图表工具有各自的
     * {@code timeout-seconds}）。这里兜的是"某个工具没有做好自己的超时、或者实现有 bug 真的
     * 卡死"这类兜不住的情况——没有这一层，{@link #execute} 底部的 {@code .block()} 会跟着永远
     * 卡住，整轮对话（乃至沿用同一个 {@link #TOOL_EXECUTION_SCHEDULER} 的其它对话）一起陪葬，
     * 和踩坑点 #92 里 6 处裸调 {@code chatModel.call()} 是同一个故障模式。5 分钟给单个工具留了
     * 远超正常预期的余量（对比逐个工具自己的超时基本都在几十秒量级），只在"确实没人兜底"时才触发。
     */
    static final Duration DEFAULT_ROUND_TIMEOUT = Duration.ofMinutes(5);

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
    private final MeterRegistry meterRegistry;
    private final Duration roundTimeout;

    ToolCallExecutor(List<ToolCallback> tools) {
        this(tools, null);
    }

    ToolCallExecutor(List<ToolCallback> tools, MeterRegistry meterRegistry) {
        this(tools, meterRegistry, DEFAULT_ROUND_TIMEOUT);
    }

    /** 测试专用：注入一个短得多的 {@code roundTimeout}，不用真的等 5 分钟才能验证超时降级。 */
    ToolCallExecutor(List<ToolCallback> tools, MeterRegistry meterRegistry, Duration roundTimeout) {
        this.toolsByName = tools.stream().collect(Collectors.toMap(
                tool -> tool.getToolDefinition().name(),
                Function.identity(),
                (first, duplicate) -> first));
        this.meterRegistry = meterRegistry;
        this.roundTimeout = roundTimeout;
    }

    List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                               ToolParamInjector paramInjector) {
        return execute(toolCalls, emit, paramInjector, null, null);
    }

    /**
     * @param sessionScopedTool 构造时的固定工具表里找不到、但这次对话请求专属的工具（比如
     *                          ToolSearch 的检索元工具本身，每个会话各有一个独立实例）；
     *                          传 null 等价于不存在这类工具
     */
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
     * @param emit         事件出口，用于实时推送 ToolStart / ToolEnd（正常轮次传 {@code context::emit}，
     *                     落库轨迹的记录也发生在这一层；暂停恢复路径此时还没有 RunContext，
     *                     传一个直接转发到原始 sink 的 lambda 即可）
     * @param mdcSnapshot  发起本次对话请求的线程的 MDC 快照；跳到 {@link #TOOL_EXECUTION_SCHEDULER}
     *                     的线程之前先还原一次，工具执行期间的日志才带得上 conversationId 这类字段
     *                     （见 {@link MdcPropagation}）。传 null 等价于不做任何还原
     * @return 与 {@code toolCalls} 一一对应、顺序一致的工具响应
     */
    List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                               ToolParamInjector paramInjector, ToolCallback sessionScopedTool,
                               Map<String, String> mdcSnapshot) {
        try {
            return Flux.fromIterable(toolCalls)
                    .flatMapSequential(toolCall -> Mono
                            .fromCallable(() -> MdcPropagation.call(mdcSnapshot,
                                    () -> executeOne(toolCall, emit, paramInjector, sessionScopedTool)))
                            .subscribeOn(TOOL_EXECUTION_SCHEDULER))
                    .collectList()
                    .timeout(roundTimeout)
                    .block();
        } catch (RuntimeException failure) {
            if (!(failure.getCause() instanceof TimeoutException)) {
                throw failure;
            }
            log.warn("本轮 {} 个工具调用超过 {} 未全部完成，按超时降级为错误结果喂回模型",
                    toolCalls.size(), roundTimeout);
            return toolCalls.stream()
                    .map(toolCall -> timeoutResponse(toolCall, emit))
                    .toList();
        }
    }

    /**
     * 超时时说不清楚这次调用到底跑到哪一步——{@code collectList()} 只在整条 Flux 完成时才
     * 一次性吐出结果，个别调用即使已经悄悄跑完也拿不到；统一按"没跑完"处理，同时补一个
     * {@code ToolEnd} 让前端不会有一个 {@code ToolStart} 永远等不到收尾，也补一次
     * {@code agenttrail.tool.calls} 计数——第一版实现漏了这一步，超时只进日志、不进指标，
     * Grafana 面板上完全看不出来，靠人去翻日志才发现，这不是"能不能上生产"该有的可观测性。
     */
    private ToolResponse timeoutResponse(ToolCall toolCall, Consumer<AgentStreamEvent> emit) {
        String result = errorPayload("tool call timed out after " + roundTimeout.toMinutes() + " minutes");
        emit.accept(new AgentStreamEvent.ToolEnd(toolCall.name(), toolCall.id(), result));
        recordTimeoutMetric(toolCall.name());
        return new ToolResponse(toolCall.id(), toolCall.name(), result);
    }

    /**
     * 只加计数，不补 {@code agenttrail.tool.duration}：超时的调用到底跑了多久是未知的
     * （可能刚起步就被整轮超时拖下水，也可能已经跑了 4 分 59 秒），编一个耗时数字进 Timer
     * 会污染这个指标的分布，比"没有这条数据"更误导人。
     */
    private void recordTimeoutMetric(String toolName) {
        if (meterRegistry == null) {
            return;
        }
        incrementCallCounter(toolName, "timeout");
    }

    private ToolResponse executeOne(ToolCall toolCall, Consumer<AgentStreamEvent> emit,
                                    ToolParamInjector paramInjector, ToolCallback sessionScopedTool) {
        Timer.Sample sample = meterRegistry == null ? null : Timer.start(meterRegistry);
        ToolCallback tool = resolve(toolCall.name(), sessionScopedTool);
        if (tool == null) {
            // 模型幻觉出的工具：连参数都不必处理，直接把错误当结果喂回去
            emit.accept(new AgentStreamEvent.ToolStart(toolCall.name(), toolCall.id(), toolCall.arguments()));
            String result = errorPayload("unknown tool: " + toolCall.name());
            emit.accept(new AgentStreamEvent.ToolEnd(toolCall.name(), toolCall.id(), result));
            recordToolMetrics(toolCall.name(), sample, false);
            return new ToolResponse(toolCall.id(), toolCall.name(), result);
        }

        // 先兜底参数合法性，再注入系统级参数——注入依赖参数是可解析的 JSON 对象
        String arguments = paramInjector.inject(sanitizeArguments(toolCall), tool.getToolDefinition());
        emit.accept(new AgentStreamEvent.ToolStart(toolCall.name(), toolCall.id(), arguments));

        String result;
        boolean success = true;
        try {
            result = tool.call(arguments);
            success = !looksLikeFailure(result);
        } catch (Exception failure) {
            // MCP 超时、远端 5xx、参数校验异常都只是这一项工具调用失败。把错误作为
            // ToolResponse 喂回模型，它才能换查询或基于已有资料继续；向外抛会取消整轮
            // Flux，DeepResearch 并发任务还会进一步产生 onErrorDropped 噪音。
            String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            result = errorPayload("tool execution failed: " + detail);
            success = false;
        }

        emit.accept(new AgentStreamEvent.ToolEnd(toolCall.name(), toolCall.id(), result));
        // 用模型原始的 toolCall.arguments()，不是上面刚注入过系统参数的 arguments——进度快照要和
        // 执行管道彻底解耦，见下面方法的说明
        emitTodoProgressIfApplicable(toolCall.name(), toolCall.arguments(), emit);
        recordToolMetrics(toolCall.name(), sample, success);
        return new ToolResponse(toolCall.id(), toolCall.name(), result);
    }

    private void recordToolMetrics(String toolName, Timer.Sample sample, boolean success) {
        if (meterRegistry == null || sample == null) {
            return;
        }
        String outcome = success ? "success" : "failure";
        Timer timer = Timer.builder("agenttrail.tool.duration")
                .description("Tool execution duration")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(meterRegistry);
        sample.stop(timer);
        incrementCallCounter(toolName, outcome);
    }

    private void incrementCallCounter(String toolName, String outcome) {
        Counter.builder("agenttrail.tool.calls")
                .description("Tool execution count")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private static boolean looksLikeFailure(String result) {
        return result != null && (result.startsWith("Error:") || result.startsWith("{\"error\""));
    }

    /**
     * {@code TodoWrite} 是唯一需要在工具执行之外额外广播一个事件的工具：进度快照必须来自
     * 重新解析的原始参数，不能依赖 {@code result}（工具的返回文本），也不能依赖
     * {@link #sanitizeArguments}/{@link ToolParamInjector} 处理过的 {@code arguments}——
     * 传的是 {@code toolCall.arguments()} 本身。今天 TodoWrite 的 inputSchema 只声明了
     * {@code todos} 一个字段，系统参数注入对它天然是空操作，所以两者暂时看不出差异；
     * 但这条隔离是设计上的保证，不是"恰好现在没事"——某天 TodoWrite 的字段和某个系统级
     * 参数撞了名，或者 sanitizeArguments 的兜底逻辑变了，这里都不该跟着悄悄改变快照内容。
     * 解析失败（结构不合法）就静默跳过，不影响这一轮工具调用本身的结果。
     */
    private void emitTodoProgressIfApplicable(String toolName, String arguments, Consumer<AgentStreamEvent> emit) {
        if (!TodoWriteTool.TOOL_NAME.equals(toolName)) {
            return;
        }
        TodoWriteTool.parseSnapshot(arguments)
                .ifPresent(items -> emit.accept(new AgentStreamEvent.TodoProgress(items)));
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
