package com.agenttrail.loop.task;

import jakarta.annotation.PreDestroy;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 跨实例任务锁：回答"这个 conversationId 现在归哪个实例跑"，只管归属，不管 Reactor 订阅本身——
 * {@link reactor.core.Disposable} 是进程内对象，永远没法放进 Redis，取消订阅这件事仍然留在
 * {@link AgentTaskManager} 手里，本类只负责"我是不是还有资格继续跑这个会话"（issue #11）。
 *
 * <h2>三个操作都要求"校验归属 + 动作"是一次原子操作</h2>
 * <ul>
 *   <li>{@link #tryAcquire}：SETNX 语义，天然原子，Redis 自带
 *   <li>{@link #renew}：先判断 value == instanceId 再 PEXPIRE——必须是一条 Lua 脚本，拆成
 *       "GET 判断" + "EXPIRE" 两条命令的话，中间那个间隙里锁可能已经因为 TTL 到期被另一个
 *       实例抢走，我们这边的 EXPIRE 就会续到别人的锁上（踩坑点 #30）
 *   <li>{@link #release}：同理，先判断归属再 DEL，同一条 Lua 脚本
 * </ul>
 *
 * <h2>TTL 自愈</h2>
 * <p>锁本身带 TTL，持有者进程崩溃、来不及主动释放时，Redis 到期自动删 key，不需要任何额外的
 * 心跳/watchdog 机制去检测"持有者是不是还活着"——这是用 TTL 换心跳复杂度的取舍。
 *
 * <h2>优雅关闭</h2>
 * <p>{@link #releaseAll()} 挂 {@code @PreDestroy}，应用关闭时主动释放本实例当前持有的全部锁，
 * 而不是放着等 TTL 过期——否则应用重启的这段窗口期，别的实例要白等一个 TTL 周期才能接手。
 *
 * <h2>定时续期</h2>
 * <p>{@link #startAutoRenewal()} 显式开启后台续期：每隔 {@code ttl/3} 把 {@link #heldConversationIds}
 * 里当前持有的每一把锁续期一遍。**不在构造函数里自动开启**——本类自己的单测（
 * {@code RedisTaskLockIT#aLockSelfHealsAfterItsOwnerDisappearsWithoutReleasingIt}）需要一个"实例
 * 还活着、但不再续期"的状态来模拟持有者崩溃，如果构造函数自动起后台线程，这类测试就没法用一个
 * 闲置实例简单模拟"崩溃不续期"，必须额外引入一层"暂停续期"的开关，反而更复杂。调用方（生产装配）
 * 需要续期时显式调用这一个方法即可。
 */
public class RedisTaskLock {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskLock.class);

    private static final String KEY_PREFIX = "agenttrail:task-lock:";

    /** 归属校验 + 续期，一次原子脚本。KEYS[1]=锁 key，ARGV[1]=期望的持有者，ARGV[2]=新 TTL(ms)。 */
    private static final String RENEW_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            else
                return 0
            end
            """;

    /** 归属校验 + 删除，一次原子脚本。KEYS[1]=锁 key，ARGV[1]=期望的持有者。 */
    private static final String RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private final RedissonClient redisson;
    private final String instanceId;
    private final Duration ttl;

    /** 本实例**认为**自己持有的会话集合，只用于优雅关闭时知道该尝试释放哪些 key、以及定时续期时
     *  该续期哪些 key——不是权威状态，权威状态永远是 Redis 里的 value 是不是等于 {@link #instanceId}。 */
    private final Set<String> heldConversationIds = ConcurrentHashMap.newKeySet();

    /** {@link #startAutoRenewal()} 显式开启后才非 null；未开启时定时续期完全不产生任何开销。 */
    private volatile ScheduledExecutorService renewalScheduler;

    public RedisTaskLock(RedissonClient redisson, String instanceId, Duration ttl) {
        this.redisson = redisson;
        this.instanceId = instanceId;
        this.ttl = ttl;
    }

    /** @return true 表示本实例拿到了锁；false 表示锁已经被别的实例持有（可能是正常运行中，也可能是它还没过期） */
    public boolean tryAcquire(String conversationId) {
        RBucket<String> bucket = redisson.getBucket(key(conversationId), StringCodec.INSTANCE);
        boolean acquired = bucket.setIfAbsent(instanceId, ttl);
        if (acquired) {
            heldConversationIds.add(conversationId);
        }
        return acquired;
    }

    /** @return true 表示确实是本实例持有该锁且续期成功；false 表示锁已不在本实例手上（被抢占或已经过期） */
    public boolean renew(String conversationId) {
        boolean renewed = evalOwnershipScript(RENEW_SCRIPT, conversationId, instanceId, String.valueOf(ttl.toMillis()));
        if (!renewed) {
            heldConversationIds.remove(conversationId);
        }
        return renewed;
    }

    /** @return true 表示确实由本实例释放了持有的锁；false 表示本来就不是本实例持有（已提前失去所有权） */
    public boolean release(String conversationId) {
        boolean released = evalOwnershipScript(RELEASE_SCRIPT, conversationId, instanceId);
        heldConversationIds.remove(conversationId);
        return released;
    }

    /**
     * 开启后台定时续期：每隔 {@code ttl/3} 调一次 {@link #renewAllHeldLocks()}。
     *
     * <p>幂等——重复调用不会开出第二个调度线程。续期间隔取 TTL 的三分之一，是"错过一次续期还有
     * 两次机会补救"和"不必要地频繁打 Redis"之间的常见折中，和 Redisson 自带 watchdog 的默认节奏
     * 是同一个量级。
     */
    public synchronized void startAutoRenewal() {
        if (renewalScheduler != null) {
            return;
        }
        renewalScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-task-lock-renewal-" + instanceId);
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1L, ttl.toMillis() / 3);
        renewalScheduler.scheduleAtFixedRate(
                this::renewAllHeldLocks, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 把当前持有的每一把锁都续期一遍；单条续期失败（网络抖动、这把锁已经被抢占）不影响其它——
     * 一批锁里的一个坏消息不该拖累这一轮其余锁的续期。由 {@link #startAutoRenewal()} 定时调用，
     * 也可以在测试里直接调用来断言续期确实发生了。
     */
    void renewAllHeldLocks() {
        for (String conversationId : Set.copyOf(heldConversationIds)) {
            try {
                if (!renew(conversationId)) {
                    log.warn("续期时发现会话 {} 的锁已经不在本实例名下（可能已被抢占或提前失去所有权）",
                            conversationId);
                }
            } catch (RuntimeException failure) {
                log.warn("续期会话 {} 的任务锁失败，等下一轮重试: {}", conversationId, failure.getMessage());
            }
        }
    }

    /**
     * 尽力释放本实例当前持有的全部锁；单个释放失败不影响其余的，失败的那部分留给 TTL 自愈兜底。
     * 释放之前先停掉续期调度——不然还没释放完，续期线程可能正好又把某把锁续了一次。
     */
    @PreDestroy
    public void releaseAll() {
        if (renewalScheduler != null) {
            renewalScheduler.shutdownNow();
        }
        for (String conversationId : Set.copyOf(heldConversationIds)) {
            try {
                release(conversationId);
            } catch (RuntimeException failure) {
                log.warn("优雅关闭时释放会话 {} 的任务锁失败，留给 TTL 自愈兜底: {}",
                        conversationId, failure.getMessage());
            }
        }
    }

    /** @param args ARGV，按脚本里引用的顺序传（RENEW 是 [instanceId, ttlMillis]，RELEASE 是 [instanceId]） */
    private boolean evalOwnershipScript(String script, String conversationId, Object... args) {
        Long result = redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, script, RScript.ReturnType.INTEGER,
                Collections.singletonList(key(conversationId)), args);
        return result != null && result == 1L;
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
