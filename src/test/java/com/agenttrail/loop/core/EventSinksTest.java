package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** issue #10：背压缓冲区必须有界，不能在慢消费端场景下无限增长直到 OOM（踩坑点 #64）。 */
class EventSinksTest {

    /** 循环发射的上限——不是缓冲区容量本身，只是"如果一直不失败，说明它不是有界的"这个判断的耐心值。 */
    private static final int PATIENCE = 10_000;

    private static final AgentStreamEvent SOME_EVENT = new AgentStreamEvent.Complete("conv-1", null);

    /**
     * 不断言具体的 {@link Sinks.EmitResult} 取值（{@code FAIL_OVERFLOW} 还是别的失败码，
     * 是 Reactor 内部队列实现的细节，不是这个类要保证的契约）——只断言"没有订阅者持续消费时，
     * 发射终归会失败"，这才是"有界"这件事本身。
     */
    @Test
    void rejectsEmissionsBeyondItsConfiguredCapacityInsteadOfGrowingForever() {
        Sinks.Many<AgentStreamEvent> sink = EventSinks.bounded(4);

        int successCount = failAfterHowManySuccesses(sink);

        assertThat(successCount).as("容量必须是有限的，不能撑到测试的耐心上限才失败").isLessThan(PATIENCE);
    }

    @Test
    void defaultBufferIsAlsoBoundedNotJustTheExplicitlySizedOne() {
        Sinks.Many<AgentStreamEvent> sink = EventSinks.bounded();

        int successCount = failAfterHowManySuccesses(sink);

        assertThat(successCount).as("默认缓冲区同样必须有限").isLessThan(PATIENCE);
    }

    /** 缓冲区满了也不该抛异常打断调用方——发事件失败只是记日志，不是致命错误。 */
    @Test
    void emitNeverThrowsEvenWhenTheBufferIsFull() {
        Sinks.Many<AgentStreamEvent> sink = EventSinks.bounded(1);
        EventSinks.emit(sink, SOME_EVENT);

        assertThatCode(() -> EventSinks.emit(sink, SOME_EVENT)).doesNotThrowAnyException();
    }

    /** @return 在第一次发射失败之前，成功发射了多少次；一直没失败则返回 {@link #PATIENCE}。 */
    private static int failAfterHowManySuccesses(Sinks.Many<AgentStreamEvent> sink) {
        for (int i = 0; i < PATIENCE; i++) {
            if (sink.tryEmitNext(SOME_EVENT).isFailure()) {
                return i;
            }
        }
        return PATIENCE;
    }
}
