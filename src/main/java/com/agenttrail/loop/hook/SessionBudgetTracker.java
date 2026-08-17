package com.agenttrail.loop.hook;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单会话 token 消耗的累加器。
 *
 * <p>有两种模式，取决于装配时给不给 {@link RedissonClient}：
 * <ul>
 *   <li><b>跨实例</b>（给了）：计数落在 Redis 的 {@link RAtomicLong} 上，同一会话被负载均衡
 *       路由到哪个实例都累加进同一个计数器。
 *   <li><b>进程内</b>（没给，或这台环境没配 Redis）：退回本地 {@link ConcurrentHashMap}，
 *       行为和历史完全一致。
 * </ul>
 *
 * <p>加跨实例模式的原因：单飞注册早就上了 Redis（{@code RedisTaskLock}），预算却一直是进程内的，
 * 于是多实例部署下同一个会话在每个实例上各算各的，{@code budgetPerSession} 实际被放大成
 * 实例个数倍——熔断线形同虚设。降级方式参照 {@code ToolRateLimiter}/{@code AgentTaskManager}
 * 已经验证过的做法：拿不到 {@link RedissonClient} 就静默退回本地，绝不让"没配 Redis"变成
 * "预算功能报错甚至应用启动失败"。
 *
 * <p>跨自然日、跨会话的精确聚合仍然依赖持久化的 TraceStore，不属于本类的范围。
 */
public class SessionBudgetTracker {

    private static final String KEY_PREFIX = "session-budget:";

    /**
     * Redis 侧计数器的兜底过期时间。正常路径上 {@link #forget} 会在会话收尾时删掉计数器，
     * 但失败收尾、进程被杀这类路径不保证走到；没有 TTL 的话 Redis 里会慢慢堆满再也没人读的 key。
     * 6 小时远长于任何一次正常会话，只在"没人来清理"时才真正生效。
     */
    static final Duration DEFAULT_TTL = Duration.ofHours(6);

    private final Map<String, AtomicLong> totalsByConversation = new ConcurrentHashMap<>();
    private final long budgetPerSession;
    /** null 表示进程内模式。 */
    private final RedissonClient redisson;
    private final Duration ttl;

    /** 进程内模式——{@code redisson} 为 null 时的行为和这个构造函数完全一致。 */
    public SessionBudgetTracker(long budgetPerSession) {
        this(null, budgetPerSession, DEFAULT_TTL);
    }

    /** 跨实例模式；{@code redisson} 传 null 等价于 {@link #SessionBudgetTracker(long)}。 */
    public SessionBudgetTracker(RedissonClient redisson, long budgetPerSession) {
        this(redisson, budgetPerSession, DEFAULT_TTL);
    }

    public SessionBudgetTracker(RedissonClient redisson, long budgetPerSession, Duration ttl) {
        this.redisson = redisson;
        this.budgetPerSession = budgetPerSession;
        this.ttl = (ttl == null) ? DEFAULT_TTL : ttl;
    }

    /** @return 累加后的总 token 数 */
    public long record(String conversationId, long promptTokens, long completionTokens) {
        long delta = promptTokens + completionTokens;
        if (redisson == null) {
            return totalsByConversation
                    .computeIfAbsent(conversationId, ignored -> new AtomicLong())
                    .addAndGet(delta);
        }
        RAtomicLong counter = redisson.getAtomicLong(key(conversationId));
        long total = counter.addAndGet(delta);
        // 每轮都续一次期：活跃会话不该因为聊得久而被 TTL 清零。和 addAndGet 不在同一次往返里，
        // 极小概率漏设一次，下一轮会补上——比为了这点原子性去写 Lua 脚本划算。
        counter.expire(ttl);
        return total;
    }

    public boolean overBudget(String conversationId) {
        if (redisson == null) {
            AtomicLong total = totalsByConversation.get(conversationId);
            return total != null && total.get() > budgetPerSession;
        }
        return redisson.getAtomicLong(key(conversationId)).get() > budgetPerSession;
    }

    /** 会话结束时清理，避免长期运行的进程（或 Redis）里这些计数器无限增长。 */
    public void forget(String conversationId) {
        if (redisson == null) {
            totalsByConversation.remove(conversationId);
            return;
        }
        redisson.getAtomicLong(key(conversationId)).delete();
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
