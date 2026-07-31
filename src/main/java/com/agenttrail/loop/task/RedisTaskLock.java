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

    /** 本实例**认为**自己持有的会话集合，只用于优雅关闭时知道该尝试释放哪些 key——
     *  不是权威状态，权威状态永远是 Redis 里的 value 是不是等于 {@link #instanceId}。 */
    private final Set<String> heldConversationIds = ConcurrentHashMap.newKeySet();

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

    /** 尽力释放本实例当前持有的全部锁；单个释放失败不影响其余的，失败的那部分留给 TTL 自愈兜底。 */
    @PreDestroy
    public void releaseAll() {
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
