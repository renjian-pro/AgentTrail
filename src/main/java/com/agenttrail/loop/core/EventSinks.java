package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * 事件 sink 的统一创建与发射入口。
 *
 * <p>{@code Sinks.many().unicast().onBackpressureBuffer()} 不传队列时是无界缓冲——
 * "长输出 + 慢消费端"场景下会随着模型持续吐字无限增长直到 OOM（踩坑点 #64）。这里统一换成
 * 有界队列：缓冲区满了就让 {@code tryEmitNext} 失败，而不是继续囤积等着撑爆堆内存。
 *
 * <p>发射统一走 {@link #emit}，失败与否都能在一处看到日志，而不是十几个调用点各自决定
 * 要不要处理 {@link Sinks.EmitResult}——发事件失败只记日志、不抛异常，事件丢一条不该打断
 * 整个循环的执行。
 */
final class EventSinks {

    private static final Logger log = LoggerFactory.getLogger(EventSinks.class);

    /** 单次对话的事件量级不大（文本片段 + 少量工具/待办事件），几百条足够覆盖正常场景，
     *  又不至于在消费端异常挂起时无限堆积。 */
    static final int DEFAULT_BUFFER_SIZE = 256;

    private EventSinks() {
    }

    static Sinks.Many<AgentStreamEvent> bounded() {
        return bounded(DEFAULT_BUFFER_SIZE);
    }

    static Sinks.Many<AgentStreamEvent> bounded(int size) {
        return Sinks.many().unicast().onBackpressureBuffer(Queues.<AgentStreamEvent>get(size).get());
    }

    static void emit(Sinks.Many<AgentStreamEvent> sink, AgentStreamEvent event) {
        // ToolCallExecutor 会并发执行同一轮的多个工具；Reactor sink 拒绝并发的 tryEmitNext，
        // 直接调用会得到 FAIL_NON_SERIALIZED 并静默丢事件。以 sink 自身作为细粒度锁，只串行化
        // 极短的入队动作，不会把真正耗时的工具调用串行化。
        Sinks.EmitResult result;
        synchronized (sink) {
            result = sink.tryEmitNext(event);
        }
        if (result.isFailure()) {
            log.warn("事件发射失败（{}），事件被丢弃: {}", result, event);
        }
    }
}
