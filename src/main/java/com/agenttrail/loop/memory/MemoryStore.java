package com.agenttrail.loop.memory;

import java.util.List;

/**
 * 长期记忆的存取接口（issue #19）。内存版（{@link InMemoryMemoryStore}）先起步，接口本身
 * 不假设存储介质——JDBC/Redis 实现只需要把 {@link MemoryItem} 序列化落地，接口契约不用变
 * （参照 {@link com.agenttrail.loop.pause.PauseStateStore}/
 * {@link com.agenttrail.loop.tools.idempotency.IdempotencyStore} 的既有模式）。
 *
 * <p>只留 {@code save}/{@code findByUserId} 两个方法——这一层目前不需要按条目单独查找、删除
 * 或语义检索（那是 Phase 4 向量库支撑的跨会话语义摘要层的事）。
 */
public interface MemoryStore {

    /** 追加一条记忆；去重/合并冲突不在这一层做，见 {@link MemoryExtractor} 的取舍说明。 */
    void save(MemoryItem item);

    /** 按用户读取全部记忆，按保存顺序返回。 */
    List<MemoryItem> findByUserId(String userId);
}
