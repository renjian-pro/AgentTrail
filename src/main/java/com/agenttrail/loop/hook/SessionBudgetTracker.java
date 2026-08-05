package com.agenttrail.loop.hook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单会话 token 消耗的进程内累加器。
 *
 * <p>这是一个有意保持轻量的单实例实现：进程重启或请求被路由到另一实例后，内存中的累计值会
 * 清零。跨实例、跨自然日的精确聚合依赖持久化 TraceStore，不属于本票范围。
 */
public class SessionBudgetTracker {

    private final Map<String, AtomicLong> totalsByConversation = new ConcurrentHashMap<>();
    private final long budgetPerSession;

    public SessionBudgetTracker(long budgetPerSession) {
        this.budgetPerSession = budgetPerSession;
    }

    /** @return 累加后的总 token 数 */
    public long record(String conversationId, long promptTokens, long completionTokens) {
        return totalsByConversation
                .computeIfAbsent(conversationId, ignored -> new AtomicLong())
                .addAndGet(promptTokens + completionTokens);
    }

    public boolean overBudget(String conversationId) {
        AtomicLong total = totalsByConversation.get(conversationId);
        return total != null && total.get() > budgetPerSession;
    }

    /** 会话结束时清理，避免长期运行的进程里这个 Map 无限增长。 */
    public void forget(String conversationId) {
        totalsByConversation.remove(conversationId);
    }
}
