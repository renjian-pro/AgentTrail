package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.tools.search.ToolSearchSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次完整推理（可能跨多轮）共享的运行时上下文。
 *
 * <p>把这些东西收成一个对象，是因为循环的递归发生在异步回调里：每一层都必须看到
 * **同一份** messages 和轮次计数，逐个参数往下传既啰嗦又容易漏传其中一个。
 *
 * @param question      用户本轮提问，压缩摘要时用它引导该保留什么
 * @param params        运行时参数（会话 id、用户 id、系统级工具参数）
 * @param messages      消息历史，跨轮累积，会被原地修改
 * @param sink          事件出口
 * @param roundCounter  已发起的轮次数
 * @param startTimeMillis 本轮问答开始时刻，落库时用来算总耗时
 * @param toolSearchSession 本次对话专属的延迟工具发现状态；未启用 ToolSearch 时为 null
 * @param mdcSnapshot   发起这次请求的线程的 MDC 快照；工具执行会跳到独立调度器的线程上，
 *                      跳之前拿这份快照还原一次，日志里的 conversationId 等字段才不会断线（见 MdcPropagation）
 * @param toolTimeline  本轮工具调用的落库轨迹，按 toolCallId 索引、按发起顺序迭代；
 *                      {@link #emit} 里随 ToolStart/ToolEnd 原地维护，收尾时序列化进 timeline 列
 * @param consecutiveToolFailures 按工具名统计的"连续失败"计数，跨这次推理的多轮递增/清零；
 *                      {@code maxConsecutiveToolFailures} 机制用它判断要不要提前熔断，见
 *                      {@link AgentLoopExecutor#finishRound}
 */
record RunContext(
        String question,
        RunnableParams params,
        List<Message> messages,
        Sinks.Many<AgentStreamEvent> sink,
        AtomicInteger roundCounter,
        long startTimeMillis,
        ToolSearchSession toolSearchSession,
        Map<String, String> mdcSnapshot,
        Map<String, ToolTimelineEntry> toolTimeline,
        Map<String, Integer> consecutiveToolFailures,
        AtomicReference<Optional<ToolCallback>> cachedSkillTool,
        /**
         * 本次运行里真正用过的外置提示词标识（{@code id@version#hash}），落进
         * {@code agent_trace.prompt_stamps} 供 Golden 分数归因（issue #101）。
         *
         * <p>三个来源：**模式级角色提示词**（issue #111 起，chat.system / analytics.system，
         * 每一轮都有）、上下文压缩、记忆提取。前者是 R22 加进来的——在那之前主对话轮次的这个
         * 集合永远是空的，于是 Golden 分数一变就回答不了"是不是改角色提示词改出来的"。
         *
         * <p>注意 §6.2 豁免的是**系统提示词的条件拼接代码**（日期/记忆/文件那几个区块），
         * 不是角色提示词本身——后者是 `resources/prompts/` 里的纯文本，纳管、有版本、进 stamp。
         */
        Set<String> usedPromptStamps) {

    private static final Logger log = LoggerFactory.getLogger(RunContext.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    RunContext(String question, RunnableParams params, List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
               AtomicInteger roundCounter, long startTimeMillis, ToolSearchSession toolSearchSession,
               Map<String, String> mdcSnapshot) {
        this(question, params, messages, sink, roundCounter, startTimeMillis, toolSearchSession, mdcSnapshot,
                new LinkedHashMap<>(), new HashMap<>(), new AtomicReference<>(),
                java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    String conversationId() {
        return params.conversationId();
    }

    long elapsedMillis() {
        return System.currentTimeMillis() - startTimeMillis;
    }

    /** 递增并返回本轮的轮次序号（从 1 开始）。 */
    int nextRound() {
        return roundCounter.incrementAndGet();
    }

    /**
     * 事件出口，顺带原地维护 {@link #toolTimeline}。
     *
     * <p><b>整个方法体在 {@code sink} 这把锁里。</b>{@code ToolCallExecutor} 用
     * {@code flatMapSequential} + {@code subscribeOn} 并发执行同一轮的多个工具，每个工具线程
     * 都会拿着 {@code context::emit} 调进来（见 {@link EventSinks#emit} 的同一段说明）——而
     * {@code toolTimeline} 是普通 {@link LinkedHashMap}：并发 {@code put} 会丢条目，让
     * {@code agent_session.timeline} 少记工具调用，还可能破坏它为保序维护的那条双向链表，
     * 使后续 {@link #toolTimelineJson()} 的迭代卡死。此前只有下面的事件投递被 {@code EventSinks}
     * 保护，上面的 timeline 维护漏在锁外，同一个并发前提只防住了一半。
     *
     * <p>锁对象和 {@link EventSinks#emit} 是同一个（{@code synchronized} 可重入，不会自锁），
     * 沿用那边的取舍：只串行化极短的入队动作，真正耗时的工具调用在锁外面，不受影响。
     */
    void emit(AgentStreamEvent event) {
        synchronized (sink) {
            switch (event) {
                case AgentStreamEvent.ToolStart start ->
                        toolTimeline.put(start.toolCallId(),
                                new ToolTimelineEntry(start.toolName(), start.toolCallId(), start.arguments()));
                case AgentStreamEvent.ToolEnd end -> {
                    ToolTimelineEntry entry = toolTimeline.get(end.toolCallId());
                    if (entry != null) entry.result = end.result();
                }
                default -> { }
            }
            EventSinks.emit(sink, event);
        }
    }

    void emitComplete() {
        sink.tryEmitComplete();
    }

    /** 序列化本轮工具调用轨迹给 {@code agent_session.timeline} 落库；没有工具调用时返回 null，
     * 和"这轮没有 timeline"的既有语义保持一致，不写一个没意义的空数组。
     *
     * <p>迭代 {@code values()} 要和 {@link #emit} 用同一把锁：收尾发生在主流程线程上，而写入
     * 来自工具执行线程，无保护的迭代可能撞上并发结构调整。 */
    String toolTimelineJson() {
        synchronized (sink) {
            if (toolTimeline.isEmpty()) {
                return null;
            }
            try {
                return JSON.writeValueAsString(toolTimeline.values());
            } catch (JsonProcessingException serializationFailure) {
                log.warn("工具调用时间线序列化失败，本轮 timeline 落库将为空", serializationFailure);
                return null;
            }
        }
    }
}
