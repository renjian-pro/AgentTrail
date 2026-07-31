package com.agenttrail.loop.pause;

import java.util.Optional;

/**
 * 暂停状态的存取接口。内存版（{@link InMemoryPauseStateStore}）先起步，接口本身不假设
 * 存储介质——JDBC/Redis 实现只需要把 {@link PauseState} 序列化落地，接口契约不用变
 * （issue #13，复用 issue #11/#12 已经接好的 Redis 基础设施是后续实现的事，不是这个接口的事）。
 */
public interface PauseStateStore {

    /** 同一个 conversationId 再次保存视为覆盖——一个会话同时只应该有一份暂停状态。 */
    void save(PauseState state);

    Optional<PauseState> find(String conversationId);

    /** @return true 表示确实删掉了一条记录；false 表示这个会话本来就没有暂停状态 */
    boolean delete(String conversationId);
}
