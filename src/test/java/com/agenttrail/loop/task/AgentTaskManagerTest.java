package com.agenttrail.loop.task;

import com.agenttrail.loop.model.AgentStreamEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskManagerTest {

    private final AgentTaskManager taskManager = new AgentTaskManager();

    @Test
    void acceptsTheFirstTaskForAConversation() {
        assertThat(taskManager.registerTask("conv-1", newSink())).isTrue();
        assertThat(taskManager.hasRunningTask("conv-1")).isTrue();
    }

    @Test
    void rejectsASecondConcurrentTaskForTheSameConversation() {
        taskManager.registerTask("conv-1", newSink());

        assertThat(taskManager.registerTask("conv-1", newSink())).isFalse();
    }

    @Test
    void allowsANewTaskOnceThePreviousOneFinished() {
        taskManager.registerTask("conv-1", newSink());
        taskManager.removeTask("conv-1");

        assertThat(taskManager.registerTask("conv-1", newSink())).isTrue();
    }

    @Test
    void keepsDifferentConversationsIndependent() {
        assertThat(taskManager.registerTask("conv-1", newSink())).isTrue();
        assertThat(taskManager.registerTask("conv-2", newSink())).isTrue();
    }

    /**
     * 踩坑点 #10：单飞注册必须是原子的。用"先查再写"的写法，两个线程可能同时查到"没有任务"
     * 然后都写入成功，同一会话就跑起了两个任务，输出交错。这里并发发起 32 次注册，
     * 必须恰好只有一个成功。
     */
    @Test
    void admitsExactlyOneWinnerWhenManyThreadsRegisterAtOnce() throws Exception {
        int contenders = 32;
        try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
            List<Callable<Boolean>> attempts = IntStream.range(0, contenders)
                    .<Callable<Boolean>>mapToObj(i -> () -> taskManager.registerTask("conv-1", newSink()))
                    .toList();

            List<Future<Boolean>> results = pool.invokeAll(attempts);

            long winners = results.stream().filter(AgentTaskManagerTest::valueOf).count();
            assertThat(winners).isEqualTo(1);
        }
    }

    /**
     * 停止必须做两件事：取消上游订阅（真正让模型停止生成，而不只是没人看），
     * 以及关闭下游事件流（否则前端一直挂着等一个永远不会到来的结束信号）。
     */
    @Test
    void stopDisposesTheSubscriptionAndClosesTheEventStream() {
        Sinks.Many<AgentStreamEvent> sink = newSink();
        taskManager.registerTask("conv-1", sink);
        Disposable subscription = Flux.interval(Duration.ofMillis(10)).subscribe();
        taskManager.setDisposable("conv-1", subscription);

        assertThat(taskManager.stopTask("conv-1")).isTrue();

        assertThat(subscription.isDisposed()).as("上游订阅必须被取消").isTrue();
        assertThat(sink.asFlux().collectList().block(Duration.ofSeconds(1)))
                .as("下游事件流必须被关闭").isEmpty();
        assertThat(taskManager.hasRunningTask("conv-1")).isFalse();
    }

    /**
     * 踩坑点 #9：循环每轮都会换一个新订阅，任务管理器必须持有**最新**的那个，
     * 否则停止作用在早就结束的第一轮订阅上，当前真正在跑的那一轮根本停不下来。
     */
    @Test
    void stopDisposesTheMostRecentlyRegisteredSubscription() {
        taskManager.registerTask("conv-1", newSink());
        Disposable firstRound = Flux.interval(Duration.ofMillis(10)).subscribe();
        Disposable secondRound = Flux.interval(Duration.ofMillis(10)).subscribe();
        taskManager.setDisposable("conv-1", firstRound);
        taskManager.setDisposable("conv-1", secondRound);

        taskManager.stopTask("conv-1");

        assertThat(secondRound.isDisposed()).as("当前轮次必须被取消").isTrue();
    }

    @Test
    void stoppingAnUnknownConversationIsANoOp() {
        assertThat(taskManager.stopTask("never-started")).isFalse();
    }

    /** 任务已经结束后再挂订阅，说明这是个孤儿订阅（如客户端已断连），应当直接释放掉而不是留着泄漏。 */
    @Test
    void disposesSubscriptionsRegisteredAfterTheTaskAlreadyEnded() {
        taskManager.registerTask("conv-1", newSink());
        taskManager.removeTask("conv-1");
        Disposable orphan = Flux.interval(Duration.ofMillis(10)).subscribe();

        taskManager.setDisposable("conv-1", orphan);

        assertThat(orphan.isDisposed()).isTrue();
    }

    private static Sinks.Many<AgentStreamEvent> newSink() {
        return Sinks.many().unicast().onBackpressureBuffer();
    }

    private static boolean valueOf(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
