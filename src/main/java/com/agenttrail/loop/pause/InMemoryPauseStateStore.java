package com.agenttrail.loop.pause;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单进程内存版 {@link PauseStateStore}——开发期、单元测试、单实例部署够用。
 *
 * <p>进程重启即遗忘、多实例各记各的：这两条和 {@code InMemoryIdempotencyStore} 是同一类局限，
 * 换 JDBC/Redis 实现才解决。这里刻意不做 TTL/过期清理——{@link PauseState} 一直等到显式
 * {@link #delete} 才消失，暂停状态不像幂等记录那样有"去重窗口"的概念，一个会话暂停多久
 * 完全取决于人什么时候来处理，不该被存储层自己按时间强制清掉。
 */
public class InMemoryPauseStateStore implements PauseStateStore {

    private final Map<String, PauseState> entries = new ConcurrentHashMap<>();

    @Override
    public void save(PauseState state) {
        entries.put(state.conversationId(), state);
    }

    @Override
    public Optional<PauseState> find(String conversationId) {
        return Optional.ofNullable(entries.get(conversationId));
    }

    @Override
    public boolean delete(String conversationId) {
        return entries.remove(conversationId) != null;
    }
}
