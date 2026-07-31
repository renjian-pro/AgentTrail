package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ChatResponses;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #10：首 token 延迟（TTFT）和后续 token 间隔（idle）必须分开计时，超时触发时
 * 上游订阅要真正被取消，不能只是下游不再收到数据——不能只信 Reactor 操作符文档，
 * 这条链路的取消语义必须有测试锁住。
 */
class LlmInvokerTest {

    @Test
    void ttftTimeoutFiresWhenNoChunkArrivesInTimeAndCancelsTheUpstreamSubscription() {
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        ChatModel neverResponds = chatModelReturning(
                Flux.<ChatResponse>never().doOnCancel(() -> upstreamCancelled.set(true)));
        LlmInvoker invoker = new LlmInvoker(neverResponds, Duration.ofMillis(80), Duration.ofSeconds(10));

        Throwable error = awaitError(invoker.streamRound(List.of(), List.of()));

        assertThat(error).isInstanceOf(TimeoutException.class);
        assertThat(upstreamCancelled).as("超时后上游订阅必须被真正取消，不只是下游停止接收").isTrue();
    }

    /** idle 超时比 ttft 短得多，用总耗时证明真正触发它的是 idle 窗口而不是 ttft 窗口。 */
    @Test
    void idleTimeoutFiresIndependentlyOfTheFirstTokenTimeout() {
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Flux<ChatResponse> oneChunkThenStall = Flux.concat(
                        Mono.just(ChatResponses.text("hi")).delayElement(Duration.ofMillis(10)),
                        Flux.never())
                .doOnCancel(() -> upstreamCancelled.set(true));
        ChatModel slowAfterFirstChunk = chatModelReturning(oneChunkThenStall);
        LlmInvoker invoker = new LlmInvoker(slowAfterFirstChunk, Duration.ofSeconds(5), Duration.ofMillis(80));

        long startedAt = System.nanoTime();
        Throwable error = awaitError(invoker.streamRound(List.of(), List.of()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(error).isInstanceOf(TimeoutException.class);
        assertThat(elapsedMillis).as("触发的必须是 80ms 的 idle 窗口，不是 5s 的 ttft 窗口").isLessThan(1000);
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void doesNotTimeOutWhenChunksKeepArrivingWithinTheIdleWindow() {
        Flux<ChatResponse> steadyChunks = Flux.just(
                        ChatResponses.text("a"), ChatResponses.text("b"), ChatResponses.text("c"))
                .delayElements(Duration.ofMillis(10));
        ChatModel steadyModel = chatModelReturning(steadyChunks);
        LlmInvoker invoker = new LlmInvoker(steadyModel, Duration.ofSeconds(1), Duration.ofMillis(200));

        List<ChatResponse> chunks = invoker.streamRound(List.of(), List.of())
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(chunks).hasSize(3);
    }

    private static Throwable awaitError(Flux<ChatResponse> flux) {
        AtomicReference<Throwable> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        flux.subscribe(chunk -> { }, error -> {
            captured.set(error);
            latch.countDown();
        }, latch::countDown);

        try {
            boolean signalled = latch.await(2, TimeUnit.SECONDS);
            assertThat(signalled).as("没有在期望的时间内收到任何终止信号").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return captured.get();
    }

    private static ChatModel chatModelReturning(Flux<ChatResponse> stream) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本类只测流式调用");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return stream;
            }
        };
    }
}
