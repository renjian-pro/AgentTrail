package com.agenttrail.loop.tools.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 给任意 {@link ToolCallback} 套上幂等能力的装饰器——写类工具的标准幂等模板。
 *
 * <h2>为什么需要它（踩坑点 #34）</h2>
 * Runtime 的中断/恢复机制只记录"执行到哪个安全点"，<b>不保证</b>挂起的那个工具没有产生副作用：
 * 中断落在工具执行阶段时，一次数据库写入可能已经提交、一次外部 API 调用可能已经发出。
 * 恢复时框架会重放挂起的工具调用，重试、跨实例重放同理——同一个写类工具被执行两次是常态而非意外。
 * 框架把幂等性责任显式留给工具方（这是正确的边界划分：框架管执行流程状态，不可能替业务判断
 * "这两次调用是不是同一笔业务"），但不该让每个工具各想各的方案，所以有了这个模板。
 *
 * <h2>为什么是装饰器而不是基类</h2>
 * 工具不需要为了拿到幂等能力改变自己的继承结构：{@code @Tool} 注解生成的
 * {@code MethodToolCallback}、MCP 客户端返回的工具、Skills 合成的工具，都不是"我们能改父类"的对象。
 * 装饰器只依赖 {@link ToolCallback} 这个接口，任何来源的工具都能包；是否要幂等、用哪种取键策略，
 * 也从工具的实现细节变成了<b>装配期的决定</b>，同一个工具在不同场景可以有不同的幂等配置。
 *
 * <h2>三种幂等模式怎么选</h2>
 * <table border="1">
 *   <caption>写类工具的三种标准幂等模式</caption>
 *   <tr><th>模式</th><th>做法</th><th>适用场景</th><th>代价</th></tr>
 *   <tr>
 *     <td><b>业务唯一键 upsert</b></td>
 *     <td>副作用本身带业务唯一键，写入走 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 *         或唯一索引冲突即忽略，去重下沉到存储引擎</td>
 *     <td>副作用<b>就是一次本库写入</b>，且天然存在业务唯一键（订单号、会话 ID + 轮次、文件路径）</td>
 *     <td>只能保护"写自己的库"这一种副作用，调外部 API / 发消息 / 扣费用不了；
 *         必须真的建唯一索引，靠应用层"先查再插"是假幂等（并发下照样双写）。
 *         <b>不需要本装饰器</b>，工具自己把 SQL 写对即可</td>
 *   </tr>
 *   <tr>
 *     <td><b>幂等 token / 去重表</b><br>（<b>本类实现的就是这种</b>）</td>
 *     <td>调用前按幂等键占位，执行成功后记下结果；重复调用直接回放首次结果，不再执行</td>
 *     <td>副作用发生在工具<b>外部</b>、没有唯一键可用（发消息、调第三方 API、生成文件），
 *         且调用方能接受"重复调用拿到的是缓存结果"</td>
 *     <td>需要一份额外的幂等记录存储；去重窗口有限（TTL 之外的重复不再拦）；
 *         占位/记结果和真正的副作用<b>不在同一个事务里</b>——副作用成功但记结果失败时，
 *         下一次重放仍会重跑，语义是"至少一次"而不是"恰好一次"；并发同键调用要等待，增加延迟</td>
 *   </tr>
 *   <tr>
 *     <td><b>Outbox（事务发件箱）</b></td>
 *     <td>工具在本地事务里只写一条"待发送"记录，外部副作用由独立投递器读表异步执行并标记完成</td>
 *     <td>一次调用<b>既要写本库、又要触发外部动作</b>，且两者必须一致
 *         （不能出现"库写了消息没发"或反过来）</td>
 *     <td>最重：要建表、要投递进程、要处理投递重试与顺序；工具从"同步返回结果"变成
 *         <b>异步最终一致</b>，模型当场只能拿到受理回执、拿不到最终结果，对话体验要跟着改</td>
 *   </tr>
 * </table>
 * <p><b>选型一句话</b>：副作用在本库且有业务唯一键 → 唯一键 upsert；副作用在外部且要同步拿结果
 * → 幂等 token（本模板）；本库写入必须和外部动作原子 → Outbox。
 *
 * <h2>本模板的执行语义</h2>
 * <ol>
 *   <li>推导幂等键（见 {@link IdempotencyKeyStrategy}），推不出就<b>透传执行</b>并打 WARN——
 *       拦下来只会让工具彻底不可用，而重复执行和不执行同样不安全</li>
 *   <li>键前面统一加工具名做命名空间，否则两个参数长得一样的不同工具会互相吃掉对方的结果</li>
 *   <li>抢到占位 → 执行委托工具 → 记下结果；执行抛异常 → <b>释放占位</b>让重试能真正重跑
 *       （代价是"至少一次"：异常可能发生在副作用已经产生之后）</li>
 *   <li>没抢到、且记录已完成 → 回放首次结果，<b>不执行</b>委托工具</li>
 *   <li>没抢到、记录还在执行中 → 轮询等待首次执行的结果；等到超时则返回一个错误结果，
 *       宁可让模型看到错误去重试，也不重复扣一次费</li>
 * </ol>
 * 冲突时返回<b>错误结果</b>而不是抛异常，是跟着 {@code ToolCallExecutor} 处理未知工具的既有约定走的：
 * 工具层面的错误以工具结果的形式喂回模型，让模型自己决定重试还是换路子，不炸掉整个 ReAct 循环。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * ToolCallback safe = IdempotentToolCallback.builder(chargeTool)
 *         .store(store)                                                  // 生产环境换 Redis/JDBC 实现
 *         .keyStrategy(IdempotencyKeyStrategies.argumentField("requestId"))
 *         .recordTtl(Duration.ofHours(24))
 *         .build();
 * }</pre>
 */
public class IdempotentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(IdempotentToolCallback.class);

    /** 工具名和推导键之间的分隔符，构成 {@code <toolName>:<derivedKey>} 形式的完整键。 */
    private static final String KEY_SEPARATOR = ":";

    /** 默认去重窗口：一天。够覆盖"用户隔了很久点了重试"，又不至于让键永远不释放。 */
    private static final Duration DEFAULT_RECORD_TTL = Duration.ofHours(24);

    /** 默认租约：5 分钟。必须大于工具的最坏执行耗时，否则执行中的调用会被别人抢走重复执行。 */
    private static final Duration DEFAULT_LEASE_TIMEOUT = Duration.ofMinutes(10);

    /** 默认等待时长：30 秒。等的是"别人正在执行的同一次调用"，超时就报冲突而不是自己再执行一遍。 */
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

    private final ToolCallback delegate;
    private final IdempotencyStore store;
    private final IdempotencyKeyStrategy keyStrategy;
    private final Duration recordTtl;
    private final Duration leaseTimeout;
    private final Duration waitTimeout;
    private final Duration pollInterval;

    private IdempotentToolCallback(Builder builder) {
        this.delegate = Objects.requireNonNull(builder.delegate, "delegate 不能为空");
        this.store = Objects.requireNonNull(builder.store, "store 不能为空");
        this.keyStrategy = builder.keyStrategy;
        this.recordTtl = builder.recordTtl;
        this.leaseTimeout = builder.leaseTimeout;
        this.waitTimeout = builder.waitTimeout;
        this.pollInterval = builder.pollInterval;
    }

    public static Builder builder(ToolCallback delegate) {
        return new Builder(delegate);
    }

    /** 全默认配置：参数摘要取键、24 小时去重窗口。参数里有显式幂等 token 的工具请用 {@link #builder}。 */
    public static IdempotentToolCallback wrap(ToolCallback delegate, IdempotencyStore store) {
        return builder(delegate).store(store).build();
    }

    /** 装饰器对模型完全透明：工具定义原样透出，模型看到的 schema 不变。 */
    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /** 元数据（如 returnDirect）必须转发，否则包一层就把委托工具的行为改掉了。 */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return executeIdempotently(toolInput, () -> delegate.call(toolInput));
    }

    /** 带 ToolContext 的重载同样要走幂等，否则调用方换个入口就绕过了保护。 */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return executeIdempotently(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String executeIdempotently(String toolInput, Supplier<String> execution) {
        ToolDefinition definition = delegate.getToolDefinition();
        Optional<String> derivedKey = keyStrategy.deriveKey(toolInput, definition);
        if (derivedKey.isEmpty()) {
            log.warn("工具 {} 本次调用推导不出幂等键，直接执行、不做去重保护", definition.name());
            return execution.get();
        }

        String key = definition.name() + KEY_SEPARATOR + derivedKey.get();
        long waitDeadlineNanos = System.nanoTime() + waitTimeout.toNanos();

        while (true) {
            IdempotencyClaim claim = store.claim(key, leaseTimeout);
            if (claim.acquired()) {
                return executeAndRecord(key, claim.ownerToken(), execution);
            }

            IdempotencyRecord record = claim.existing();
            if (record.isCompleted()) {
                log.debug("幂等键 {} 命中已完成记录，回放首次执行结果，跳过工具执行", key);
                return record.result();
            }

            // 同键调用正在别处执行：等它出结果，而不是自己再执行一遍产生第二次副作用。
            // 这里必须用轮询而不是本地 CountDownLatch——存储换成 Redis 之后，持有者可能在另一个
            // 进程里，本地闩锁根本等不到它；轮询是对所有存储实现都成立的等待方式。
            if (System.nanoTime() - waitDeadlineNanos >= 0) {
                log.warn("幂等键 {} 仍在执行中且等待超时，返回冲突结果交给模型重试", key);
                return conflictPayload(key);
            }
            if (!sleepQuietly(pollInterval)) {
                // 线程被中断（比如用户中断了整个任务）：中断标记已恢复，不要再往下执行工具
                return conflictPayload(key);
            }
        }
    }

    private String executeAndRecord(String key, String ownerToken, Supplier<String> execution) {
        String result;
        try {
            result = execution.get();
        } catch (RuntimeException | Error failure) {
            // 失败必须释放占位，否则一次网络抖动就把这个键锁死到租约到期，模型连重试机会都没有。
            // 注意这换来的是"至少一次"语义：异常也可能发生在副作用已经产生之后（比如写库成功、
            // 读返回值时超时），此时重试会真的重复执行——所以本模式要求副作用本身尽量是一次
            // 原子写入，而不是一串没有事务保护的外部调用。
            store.release(key, ownerToken);
            throw failure;
        }
        store.complete(key, ownerToken, result, recordTtl);
        return result;
    }

    /** 冲突以工具结果的形式喂回模型（同 ToolCallExecutor 处理未知工具的约定），不抛异常中断循环。 */
    private String conflictPayload(String key) {
        return "{\"error\":\"another call with the same idempotency key is still in flight, retry later\","
                + "\"idempotencyKey\":\"" + key.replace("\"", "'") + "\"}";
    }

    /** @return 正常睡醒返回 true；被中断则恢复中断标记并返回 false，由调用方决定怎么收场 */
    private boolean sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static final class Builder {

        private final ToolCallback delegate;
        private IdempotencyStore store;
        private IdempotencyKeyStrategy keyStrategy = IdempotencyKeyStrategies.argumentDigest();
        private Duration recordTtl = DEFAULT_RECORD_TTL;
        private Duration leaseTimeout = DEFAULT_LEASE_TIMEOUT;
        private Duration waitTimeout = DEFAULT_WAIT_TIMEOUT;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;

        private Builder(ToolCallback delegate) {
            this.delegate = delegate;
        }

        /** 幂等记录存哪：单机用 {@link InMemoryIdempotencyStore}，多实例换 Redis/JDBC 实现。 */
        public Builder store(IdempotencyStore store) {
            this.store = store;
            return this;
        }

        /** 怎么算"同一次调用"，默认参数摘要。 */
        public Builder keyStrategy(IdempotencyKeyStrategy keyStrategy) {
            this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy 不能为空");
            return this;
        }

        /** 去重窗口：超过这段时间的"重复"被视为一次新的业务调用。 */
        public Builder recordTtl(Duration recordTtl) {
            this.recordTtl = Objects.requireNonNull(recordTtl, "recordTtl 不能为空");
            return this;
        }

        /** 占位租约，取值必须大于工具最坏执行耗时。 */
        public Builder leaseTimeout(Duration leaseTimeout) {
            this.leaseTimeout = Objects.requireNonNull(leaseTimeout, "leaseTimeout 不能为空");
            return this;
        }

        /** 同键调用正在别处执行时最多等多久。 */
        public Builder waitTimeout(Duration waitTimeout) {
            this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout 不能为空");
            return this;
        }

        /** 等待期间的轮询间隔。 */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval 不能为空");
            return this;
        }

        public IdempotentToolCallback build() {
            return new IdempotentToolCallback(this);
        }
    }
}
