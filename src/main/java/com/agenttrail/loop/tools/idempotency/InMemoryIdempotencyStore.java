package com.agenttrail.loop.tools.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单进程内存版 {@link IdempotencyStore}。
 *
 * <p><b>它能保证什么</b>：同一个 JVM 内、同一个 store 实例下的重复调用被正确去重，
 * 包括多线程并发抢同一个键。开发期、单元测试、以及"就一个实例"的部署形态够用。
 *
 * <p><b>它不能保证什么</b>（换 Redis/JDBC 实现才解决，Phase 1 的事）：
 * <ul>
 *   <li>进程重启即遗忘——重启后重放同一个工具调用会真的再执行一次</li>
 *   <li>多实例各记各的——负载均衡把重试打到另一台，去重完全失效</li>
 * </ul>
 *
 * <h2>两个刻意的简化</h2>
 * <ul>
 *   <li><b>过期记录懒清理</b>：只在访问到这个键时才判定过期并清掉，没有后台扫描线程。
 *       键基数很大（比如用参数摘要当键）且长期不重复访问时，内存会只涨不降——
 *       这也是内存版只适合开发期的原因之一，Redis 的 {@code PX} 是引擎级过期，没有这个问题。</li>
 *   <li><b>complete/release 不校验持有者</b>：租约到期后新执行者接管，老执行者要是又活过来
 *       调 complete，会把新执行者的记录覆盖掉。严格解法是 claim 返回一个 fencing token、
 *       写回时校验（分布式锁的标准做法）。这里不做，代价换成一条使用约束：
 *       <b>租约时长必须大于工具的最坏执行耗时</b>。</li>
 * </ul>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    /** 时钟可注入：租约/TTL 过期这类行为，测试里靠拨表来断言，不靠真实 sleep。 */
    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * 用 {@link ConcurrentHashMap#compute} 实现原子的"占位或读取"。
     *
     * <p>{@code compute} 对同一个键是原子的（内部持有该 bin 的锁），所以"判断是否存在/过期"
     * 和"写入占位"这两步不会被别的线程插进来——这正是 {@link IdempotencyStore#claim} 要求的语义。
     * 换成 {@code get} + {@code putIfAbsent} 就有窗口，两个线程会同时认为自己是首次执行者。
     *
     * <p>回调里只做纯内存判断、不做 IO，这点必须守住：{@code compute} 期间持有 bin 锁，
     * 在里面做慢操作会把同键甚至同 bin 的其它线程一起拖住。真正的工具执行在装饰器里、锁外进行。
     */
    @Override
    public Optional<IdempotencyRecord> claim(String key, Duration leaseTimeout) {
        Instant now = clock.instant();
        AtomicReference<IdempotencyRecord> alreadyThere = new AtomicReference<>();
        entries.compute(key, (existingKey, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                alreadyThere.set(existing.record());
                return existing;
            }
            // 没有记录，或者记录已过期（执行者死了 / 去重窗口到期）——本次调用抢到占位
            return new Entry(IdempotencyRecord.inFlight(existingKey, now), now.plus(leaseTimeout));
        });
        return Optional.ofNullable(alreadyThere.get());
    }

    @Override
    public void complete(String key, String result, Duration recordTtl) {
        Instant now = clock.instant();
        entries.put(key, new Entry(IdempotencyRecord.completed(key, result, now), now.plus(recordTtl)));
    }

    /**
     * 只释放还没完成的占位。
     *
     * <p>加 IN_FLIGHT 判断不是防御性编程：如果无条件删除，一次迟到的 release 会把已经完成的
     * 结果记录一起删掉，去重窗口被清零，下一次重放就真的会再执行一遍工具。
     */
    @Override
    public void release(String key) {
        entries.computeIfPresent(key, (existingKey, existing) ->
                existing.record().isCompleted() ? existing : null);
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(now)) {
            // 顺手清掉，省得过期记录一直占着内存
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.record());
    }

    /** 记录 + 绝对过期时刻；IN_FLIGHT 的过期时刻是租约到期，COMPLETED 的是去重窗口到期。 */
    private record Entry(IdempotencyRecord record, Instant expiresAt) {

        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
