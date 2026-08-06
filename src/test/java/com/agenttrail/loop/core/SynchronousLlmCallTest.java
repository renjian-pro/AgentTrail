package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ChatResponses;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 踩坑点 #92：{@code PromptInjectionGuard}/{@code ToolSearchCallback} 等 6 处同步调用点原来直接
 * {@code chatModel.call(prompt)}，一次真实的 DashScope 网络卡顿（连接卡住 236 秒仍未返回）证明
 * 这类调用没有超时保护——网络层面"一直不返回"永远不会变成一个能被 try/catch 接住的异常。这里锁住
 * {@link SynchronousLlmCall} 本身的行为：正常调用透传结果，慢到超过上限就必须在有限时间内失败，
 * 而不是无限期占住调用它的线程。
 */
class SynchronousLlmCallTest {

    @Test
    void returnsTheModelResponseWhenTheCallFinishesInTime() {
        ChatModel fast = callReturning(() -> ChatResponses.text("ok"));

        ChatResponse response = SynchronousLlmCall.call(fast, new Prompt(List.of()), Duration.ofSeconds(2));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
    }

    @Test
    void usesTheDefaultTimeoutWhenNoneIsGiven() {
        assertThat(SynchronousLlmCall.DEFAULT_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }

    /** 模拟 2026-08-06 那次真实卡顿：模型调用本身既不返回也不抛异常，只是永远挂着。 */
    @Test
    void failsWithinTheConfiguredTimeoutInsteadOfHangingForeverWhenTheModelNeverResponds() {
        AtomicBoolean callInterruptedOrAbandoned = new AtomicBoolean(false);
        ChatModel neverResponds = callReturning(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException interrupted) {
                callInterruptedOrAbandoned.set(true);
                Thread.currentThread().interrupt();
            }
            return ChatResponses.text("too late");
        });

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> SynchronousLlmCall.call(neverResponds, new Prompt(List.of()), Duration.ofMillis(150)))
                .hasCauseInstanceOf(TimeoutException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(elapsedMillis)
                .as("调用方必须在配置的超时附近拿回控制权，不能等模型自己的 30 秒 sleep 走完")
                .isLessThan(2000);
        assertThat(callInterruptedOrAbandoned)
                .as("Reactor 对 boundedElastic 上的任务取消是 Future.cancel(true)——确实会发一次线程中断，"
                        + "Thread.sleep() 这种可中断阻塞能响应它；但 OkHttp 的阻塞 socket 读取通常不是可中断的，"
                        + "所以生产事故里那条真实卡住的调用不会因为这个中断就真的解除阻塞，应用层拿回控制权靠的是"
                        + "Mono.timeout() 让订阅立刻转出错误，不依赖线程真的被中断")
                .isTrue();
    }

    @Test
    void propagatesTheOriginalFailureWhenTheModelCallThrowsBeforeAnyTimeout() {
        ChatModel failingFast = callReturning(() -> {
            throw new IllegalStateException("模型不可用");
        });

        assertThatThrownBy(() -> SynchronousLlmCall.call(failingFast, new Prompt(List.of()), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ChatModel callReturning(Supplier<ChatResponse> supplier) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return supplier.get();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("本类只测同步调用");
            }
        };
    }
}
