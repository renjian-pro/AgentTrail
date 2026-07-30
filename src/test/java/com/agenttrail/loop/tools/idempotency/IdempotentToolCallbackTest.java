package com.agenttrail.loop.tools.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentToolCallbackTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);

    @Test
    void firstCallExecutesTheDelegateAndReturnsItsResult() {
        SideEffectTool tool = new SideEffectTool("createOrder", "{\"orderId\":\"A-1\"}");
        ToolCallback idempotent = IdempotentToolCallback.wrap(tool, store);

        String result = idempotent.call("{\"amount\":100}");

        assertThat(result).isEqualTo("{\"orderId\":\"A-1\"}");
        assertThat(tool.executions()).isEqualTo(1);
    }

    /**
     * 本票的核心验收：同一次调用重放时，委托工具**不能**被再执行一次，
     * 返回的必须是首次执行缓存下来的结果（踩坑点 #34：断点恢复会重放挂起的工具调用）。
     */
    @Test
    void repeatedCallReplaysTheCachedResultWithoutExecutingTheDelegateAgain() {
        SideEffectTool tool = new SideEffectTool("createOrder", "{\"orderId\":\"A-1\"}");
        ToolCallback idempotent = IdempotentToolCallback.wrap(tool, store);

        String first = idempotent.call("{\"amount\":100}");
        String second = idempotent.call("{\"amount\":100}");

        assertThat(second).isEqualTo(first);
        assertThat(tool.executions()).as("副作用只能发生一次").isEqualTo(1);
    }

    /** 模型两次生成的 JSON 字段顺序经常不同，规范化之后必须算作同一次调用。 */
    @Test
    void argumentFieldOrderDoesNotBreakDeduplication() {
        SideEffectTool tool = new SideEffectTool("createOrder", "ok");
        ToolCallback idempotent = IdempotentToolCallback.wrap(tool, store);

        idempotent.call("{\"amount\":100,\"sku\":\"X\"}");
        idempotent.call("{\"sku\":\"X\",\"amount\":100}");

        assertThat(tool.executions()).isEqualTo(1);
    }

    @Test
    void differentArgumentsAreDifferentCalls() {
        SideEffectTool tool = new SideEffectTool("createOrder", "ok");
        ToolCallback idempotent = IdempotentToolCallback.wrap(tool, store);

        idempotent.call("{\"amount\":100}");
        idempotent.call("{\"amount\":200}");

        assertThat(tool.executions()).isEqualTo(2);
    }

    /**
     * 幂等 token 模式的意义就在这里：业务上"同一笔请求"由 token 定义，
     * 哪怕其它参数被模型重新措辞过，只要 token 没变就还是同一次。
     */
    @Test
    void sameExplicitTokenDeduplicatesEvenWhenOtherArgumentsDiffer() {
        SideEffectTool tool = new SideEffectTool("charge", "ok");
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(store)
                .keyStrategy(IdempotencyKeyStrategies.argumentField("requestId"))
                .build();

        idempotent.call("{\"requestId\":\"req-1\",\"memo\":\"第一次描述\"}");
        idempotent.call("{\"requestId\":\"req-1\",\"memo\":\"换了个说法\"}");

        assertThat(tool.executions()).isEqualTo(1);
    }

    @Test
    void differentExplicitTokensAreDifferentCalls() {
        SideEffectTool tool = new SideEffectTool("charge", "ok");
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(store)
                .keyStrategy(IdempotencyKeyStrategies.argumentField("requestId"))
                .build();

        idempotent.call("{\"requestId\":\"req-1\"}");
        idempotent.call("{\"requestId\":\"req-2\"}");

        assertThat(tool.executions()).isEqualTo(2);
    }

    /** 推导不出幂等键时透传执行（而不是把工具拦死），这是刻意的取舍，必须锁在测试里。 */
    @Test
    void passesThroughWhenNoIdempotencyKeyCanBeDerived() {
        SideEffectTool tool = new SideEffectTool("charge", "ok");
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(store)
                .keyStrategy(IdempotencyKeyStrategies.argumentField("requestId"))
                .build();

        idempotent.call("{\"memo\":\"没带 token\"}");
        idempotent.call("{\"memo\":\"没带 token\"}");

        assertThat(tool.executions()).isEqualTo(2);
    }

    @Test
    void explicitTokenFallsBackToTheArgumentDigestWhenMissing() {
        SideEffectTool tool = new SideEffectTool("charge", "ok");
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(store)
                .keyStrategy(IdempotencyKeyStrategies.argumentFieldOrDigest("requestId"))
                .build();

        idempotent.call("{\"memo\":\"没带 token\"}");
        idempotent.call("{\"memo\":\"没带 token\"}");

        assertThat(tool.executions()).isEqualTo(1);
    }

    /** 幂等键必须按工具名分命名空间，否则两个参数长得一样的不同工具会互相吃掉对方的结果。 */
    @Test
    void identicalArgumentsOnDifferentToolsDoNotCollide() {
        SideEffectTool charge = new SideEffectTool("charge", "charged");
        SideEffectTool refund = new SideEffectTool("refund", "refunded");

        assertThat(IdempotentToolCallback.wrap(charge, store).call("{\"amount\":100}")).isEqualTo("charged");
        assertThat(IdempotentToolCallback.wrap(refund, store).call("{\"amount\":100}")).isEqualTo("refunded");
        assertThat(charge.executions()).isEqualTo(1);
        assertThat(refund.executions()).isEqualTo(1);
    }

    /**
     * 执行抛异常时占位必须被释放：否则一次网络抖动就把这个幂等键锁死到租约到期，
     * 模型连重试的机会都没有。代价是"至少一次"语义，见类注释。
     */
    @Test
    void failedExecutionReleasesTheKeySoARetryCanExecuteAgain() {
        AtomicInteger attempts = new AtomicInteger();
        SideEffectTool tool = new SideEffectTool("charge", arguments -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("下游超时");
            }
            return "ok";
        });
        ToolCallback idempotent = IdempotentToolCallback.wrap(tool, store);

        assertThatThrownBy(() -> idempotent.call("{\"amount\":100}"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(idempotent.call("{\"amount\":100}")).isEqualTo("ok");
        assertThat(tool.executions()).isEqualTo(2);
    }

    /** ToolContext 那条调用路径同样要走幂等，不能只保护无上下文的重载。 */
    @Test
    void deduplicatesTheToolContextOverloadAsWell() {
        SideEffectTool tool = new SideEffectTool("createOrder", "ok");
        ToolCallback idempotent = IdempotentToolCallback.wrap(tool, store);
        ToolContext context = new ToolContext(Map.of("userId", "u-1"));

        idempotent.call("{\"amount\":100}", context);
        idempotent.call("{\"amount\":100}", context);

        assertThat(tool.executions()).isEqualTo(1);
    }

    /**
     * 并发下同一个键同时进来：只有一个线程能真正执行，其余线程等它完成后拿到**同一个结果**。
     * 用"先查再执行"的写法这里会漏出多次执行——和 AgentTaskManager 的单飞注册是同一类竞态。
     */
    @Test
    void executesExactlyOnceWhenManyThreadsCallWithTheSameKeyAtOnce() throws Exception {
        SideEffectTool tool = new SideEffectTool("charge", arguments -> {
            sleepQuietly(Duration.ofMillis(80));
            return "charged";
        });
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(new InMemoryIdempotencyStore())
                .waitTimeout(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(10))
                .build();

        int contenders = 32;
        try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
            List<Callable<String>> attempts = IntStream.range(0, contenders)
                    .<Callable<String>>mapToObj(i -> () -> idempotent.call("{\"amount\":100}"))
                    .toList();

            List<Future<String>> results = pool.invokeAll(attempts);

            assertThat(results.stream().map(IdempotentToolCallbackTest::valueOf))
                    .as("每个调用方都要拿到首次执行的结果").containsOnly("charged");
        }
        assertThat(tool.executions()).as("副作用只能发生一次").isEqualTo(1);
    }

    /**
     * 同键调用还在别处执行、等到超时也没等到结果时，返回错误结果而不是执行第二遍——
     * "宁可让模型看到一个错误并重试，也不重复扣一次费"。
     * 错误以工具结果的形式喂回模型（和 ToolCallExecutor 处理未知工具的方式一致），不抛异常炸掉整个循环。
     */
    @Test
    void reportsAConflictInsteadOfExecutingWhenTheSameKeyIsStillInFlight() {
        SideEffectTool tool = new SideEffectTool("charge", "charged");
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(store)
                .keyStrategy(fixedKey())
                .waitTimeout(Duration.ZERO)
                .build();
        store.claim("charge:fixed", LEASE);

        String result = idempotent.call("{\"amount\":100}");

        assertThat(result).contains("\"error\"");
        assertThat(tool.executions()).isZero();
    }

    /**
     * 上一个执行者死在半路（进程被杀、或中断恰好落在 TOOL_EXECUTION 安全点）——
     * 租约过期后同键调用必须能重新执行，否则这个键永远卡死。
     */
    @Test
    void executesAgainOnceTheLeaseOfADeadExecutionExpired() {
        SideEffectTool tool = new SideEffectTool("charge", "charged");
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(store)
                .keyStrategy(fixedKey())
                .leaseTimeout(LEASE)
                .build();
        store.claim("charge:fixed", LEASE);

        clock.advance(LEASE.plusSeconds(1));
        String result = idempotent.call("{\"amount\":100}");

        assertThat(result).isEqualTo("charged");
        assertThat(tool.executions()).isEqualTo(1);
    }

    /** 去重窗口过期之后，同一个键要能重新执行——不然"重复"和"过了很久再做一次"就分不开了。 */
    @Test
    void executesAgainAfterTheDeduplicationWindowExpired() {
        SideEffectTool tool = new SideEffectTool("charge", "charged");
        ToolCallback idempotent = IdempotentToolCallback.builder(tool)
                .store(store)
                .recordTtl(Duration.ofMinutes(10))
                .build();

        idempotent.call("{\"amount\":100}");
        clock.advance(Duration.ofMinutes(11));
        idempotent.call("{\"amount\":100}");

        assertThat(tool.executions()).isEqualTo(2);
    }

    /** 装饰器对模型是完全透明的：工具定义和元数据必须原样透出去。 */
    @Test
    void exposesTheDelegateToolDefinitionUnchanged() {
        SideEffectTool tool = new SideEffectTool("charge", "charged");

        ToolCallback idempotent = IdempotentToolCallback.wrap(tool, store);

        assertThat(idempotent.getToolDefinition().name()).isEqualTo("charge");
        assertThat(idempotent.getToolMetadata()).isEqualTo(tool.getToolMetadata());
    }

    private static IdempotencyKeyStrategy fixedKey() {
        return (toolInput, definition) -> Optional.of("fixed");
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String valueOf(Future<String> future) {
        try {
            return future.get();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** 会产生外部副作用的工具替身：执行次数就是"副作用发生了几次"。 */
    private static final class SideEffectTool implements ToolCallback {

        private final ToolDefinition definition;
        private final Function<String, String> behaviour;
        private final AtomicInteger executions = new AtomicInteger();

        SideEffectTool(String name, String result) {
            this(name, arguments -> result);
        }

        SideEffectTool(String name, Function<String, String> behaviour) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description("产生外部副作用的写类工具")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
            this.behaviour = behaviour;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            executions.incrementAndGet();
            return behaviour.apply(toolInput);
        }

        int executions() {
            return executions.get();
        }
    }
}
