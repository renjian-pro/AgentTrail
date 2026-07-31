package com.agenttrail.loop.ppt;

import java.util.Optional;

/**
 * PPT 生成任务的存取接口（issue #24）。参照 {@code PauseStateStore}/{@code FileStore} 的既有
 * 模式：接口不假设存储介质，先有内存实现（快速单测用）+ JDBC 实现（生产用）。
 */
public interface PptTaskStore {

    /** 创建一条新任务，初始状态固定是 {@link PptState#INIT}，返回生成的主键。 */
    long create(String conversationId, PptGenerationContext initialContext);

    Optional<PptTask> findById(long id);

    /**
     * 状态推进：调用方（{@link PptGenerationService}）只应该在 {@code newState}
     * 对应的上一个状态的副作用已经真正完成之后才调用这个方法——这里本身不做任何时序校验，
     * 时序正确性是调用方的职责，这里只管把这次推进原子落库，同时清空 {@code errorMsg}
     * （上一次失败的痕迹不该跟着一次成功的推进继续挂着）。
     */
    void advance(long id, PptState newState, PptGenerationContext context);

    /** 某个状态执行失败：{@code status} 保持是这个失败的状态本身（下次重新跑它整个状态），只更新 errorMsg。 */
    void markFailed(long id, PptState failedState, String errorMsg);
}
