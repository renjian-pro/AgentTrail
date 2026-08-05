package com.agenttrail.loop.trace;

import java.util.List;
import java.util.Optional;

/**
 * 追踪记录的存取接口。内存版（{@link InMemoryTraceStore}）先起步，接口本身不假设存储介质——
 * JDBC/Redis 实现只需要把 {@link TraceRecord} 序列化落地，接口契约不用变（issue #17，
 * 参照 {@link com.agenttrail.loop.pause.PauseStateStore}/{@link com.agenttrail.loop.tools.idempotency.IdempotencyStore}
 * 的既有模式）。
 */
public interface TraceStore {

    /** 追加一条记录——同一会话的多轮各自独立保存，不覆盖。 */
    void save(TraceRecord record);

    /** 按会话查询全部记录，按落库顺序返回。 */
    List<TraceRecord> findByConversationId(String conversationId);

    /** 校验持久化审计记录的哈希链；内存实现没有可被外部篡改的持久化边界。 */
    Optional<Integer> verifyChain(String conversationId);
}
