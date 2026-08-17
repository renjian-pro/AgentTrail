package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code toolTimeline} 的并发写回归：{@code ToolCallExecutor} 用 {@code flatMapSequential} +
 * {@code subscribeOn} 并发执行同一轮的多个工具，每个工具线程都拿着 {@code context::emit} 调进来。
 *
 * <p>修复前 {@link RunContext#emit} 里的 timeline 维护漏在 {@code EventSinks} 那把锁外面，
 * 多线程并发 {@code put} 一个普通 {@code LinkedHashMap}：轻则丢条目（落库的 timeline 少记
 * 工具调用），重则破坏它为保序维护的双向链表，让收尾时的 {@code values()} 迭代卡死。
 */
class RunContextTimelineConcurrencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static RunContext newContext(Sinks.Many<AgentStreamEvent> sink) {
        return new RunContext("q", new RunnableParams("conv-1", "user-1"), new ArrayList<>(), sink,
                new AtomicInteger(0), System.currentTimeMillis(), null, null);
    }

    /**
     * 并发缺陷是概率性的：去掉锁之后单轮复现率实测只有 1/3 左右（JIT 预热、线程调度都会影响），
     * 单跑一轮的回归测试在 CI 上会时红时绿，起不到守门作用。这里重复若干轮，把"锁被去掉"压成
     * 一个稳定可见的失败。
     */
    @Test
    void concurrentToolEventsKeepEveryTimelineEntry() throws Exception {
        for (int round = 0; round < 6; round++) {
            assertEveryEntrySurvivesConcurrentEmits();
        }
    }

    private void assertEveryEntrySurvivesConcurrentEmits() throws Exception {
        int toolCalls = 300;
        // 缓冲区给够：sink 溢出只会丢事件、不影响 timeline，但会刷一屏 warn 日志干扰阅读
        RunContext context = newContext(EventSinks.bounded(2048));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startLine = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < toolCalls; i++) {
            String callId = "call-" + i;
            futures.add(pool.submit(() -> {
                // 所有线程卡在同一条起跑线上，最大化真正重叠的写入窗口
                startLine.await();
                context.emit(new AgentStreamEvent.ToolStart("echo", callId, "{}"));
                context.emit(new AgentStreamEvent.ToolEnd("echo", callId, "ok-" + callId));
                return null;
            }));
        }
        startLine.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get();
        }

        JsonNode timeline = JSON.readTree(context.toolTimelineJson());
        assertThat(timeline).hasSize(toolCalls);
        // 每个条目都要拿到自己那次 ToolEnd 的结果——ToolEnd 覆盖错条目同样是并发写的表现
        for (JsonNode entry : timeline) {
            assertThat(entry.get("result").asText())
                    .isEqualTo("ok-" + entry.get("toolCallId").asText());
        }
    }

    @Test
    void sequentialToolEventsKeepInsertionOrder() throws Exception {
        RunContext context = newContext(EventSinks.bounded(2048));

        for (int i = 0; i < 5; i++) {
            context.emit(new AgentStreamEvent.ToolStart("echo", "call-" + i, "{}"));
            context.emit(new AgentStreamEvent.ToolEnd("echo", "call-" + i, "ok"));
        }

        JsonNode timeline = JSON.readTree(context.toolTimelineJson());
        List<String> ids = new ArrayList<>();
        timeline.forEach(entry -> ids.add(entry.get("toolCallId").asText()));
        // 落库顺序必须是模型发起工具调用的顺序，加锁不能把 LinkedHashMap 的保序语义弄丢
        assertThat(ids).containsExactly("call-0", "call-1", "call-2", "call-3", "call-4");
    }

    @Test
    void noToolCallsStillSerializesToNull() {
        RunContext context = newContext(EventSinks.bounded(64));

        context.emit(new AgentStreamEvent.Text("hello"));

        assertThat(context.toolTimelineJson()).isNull();
    }
}
