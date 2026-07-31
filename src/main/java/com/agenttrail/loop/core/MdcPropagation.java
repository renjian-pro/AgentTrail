package com.agenttrail.loop.core;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 手写 loop 没有走单一声明式 Reactor 链——工具执行显式 {@code subscribeOn} 到独立调度器
 * （见 {@link ToolCallExecutor}），MDC 这种 ThreadLocal 状态过了这条线程边界就读不到了，
 * 日志里 conversationId 这类字段会在工具执行的日志行上突然消失。
 *
 * <p>这里不接 Reactor Context / Micrometer context-propagation 这类通用传播机制——手写 loop
 * 里真正发生线程切换的地方数量固定且已知（目前只有工具执行这一处显式 {@code subscribeOn}），
 * 加一层通用框架换来的只是间接层，不是收益。做法是在请求发起时拍一次 MDC 快照
 * （见 {@link AgentLoopExecutor#stream}），跳线程前显式还原、跳完显式清理。
 *
 * <p>执行完必须恢复"跳之前的状态"而不是无脑 {@link MDC#clear()}——线程来自复用的调度器池，
 * 清空一次性快照后如果不把原来的内容放回去，池子里的线程会把这次任务的 MDC 泄漏给下一个任务。
 */
final class MdcPropagation {

    private MdcPropagation() {
    }

    static <T> T call(Map<String, String> snapshot, Callable<T> action) throws Exception {
        if (snapshot == null) {
            return action.call();
        }
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.setContextMap(snapshot);
        try {
            return action.call();
        } finally {
            restore(previous);
        }
    }

    private static void restore(Map<String, String> previous) {
        if (previous != null) {
            MDC.setContextMap(previous);
        } else {
            MDC.clear();
        }
    }
}
