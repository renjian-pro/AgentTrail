package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次完整推理（可能跨多轮）共享的运行时上下文。
 *
 * <p>把这些东西收成一个对象，是因为循环的递归发生在异步回调里：每一层都必须看到
 * **同一份** messages 和轮次计数，逐个参数往下传既啰嗦又容易漏传其中一个。
 *
 * @param question     用户本轮提问，压缩摘要时用它引导该保留什么
 * @param params       运行时参数（会话 id、用户 id、系统级工具参数）
 * @param messages     消息历史，跨轮累积，会被原地修改
 * @param sink         事件出口
 * @param roundCounter 已发起的轮次数
 */
record RunContext(
        String question,
        RunnableParams params,
        List<Message> messages,
        Sinks.Many<AgentStreamEvent> sink,
        AtomicInteger roundCounter) {

    String conversationId() {
        return params.conversationId();
    }

    /** 递增并返回本轮的轮次序号（从 1 开始）。 */
    int nextRound() {
        return roundCounter.incrementAndGet();
    }

    void emit(AgentStreamEvent event) {
        sink.tryEmitNext(event);
    }

    void emitComplete() {
        sink.tryEmitComplete();
    }
}
