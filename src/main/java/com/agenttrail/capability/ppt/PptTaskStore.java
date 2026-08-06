package com.agenttrail.capability.ppt;

import java.util.List;
import java.util.Optional;

/**
 * PPT 生成任务的存取接口（issue #24）。参照 {@code PauseStateStore}/{@code FileStore} 的既有
 * 模式：接口不假设存储介质，先有内存实现（快速单测用）+ JDBC 实现（生产用）。
 */
public interface PptTaskStore {

    /** 创建一条新任务，初始状态固定是 {@link PptState#INIT}，返回生成的主键。 */
    default long create(String conversationId, PptGenerationContext initialContext) {
        return create("legacy", conversationId, initialContext);
    }

    long create(String userId, String conversationId, PptGenerationContext initialContext);

    Optional<PptTask> findById(long id);

    default Optional<PptTask> findById(String userId, long id) {
        return findById(id).filter(task -> userId == null || userId.equals(task.userId()));
    }

    /**
     * 按 conversationId 找该会话下最近一条任务（issue #32）——{@code MODIFY}/{@code RESUME} 两个
     * 分支都是"用户在对话里说了一句话"触发的，调用方（{@link PptGenerationService}）手上只有
     * conversationId，没有也不该要求调用方自己维护 taskId：{@code RESUME} 想接着跑"最近一次没跑完
     * 的"，{@code MODIFY} 想在"最近一次跑完的"基础上改，两者语义都是"这个会话下最新的一条"，
     * 不是"随便挑一条"——一个会话可能有多条历史任务（比如 MODIFY 每次都新建一条任务行，见
     * {@link PptGenerationService} 里的实现），取最新的这条才对。
     */
    Optional<PptTask> findLatestByConversationId(String conversationId);

    default Optional<PptTask> findLatestByConversationId(String userId, String conversationId) {
        return findLatestByConversationId(conversationId)
                .filter(task -> userId == null || userId.equals(task.userId()));
    }

    /**
     * 状态推进：调用方（{@link PptGenerationService}）只应该在 {@code newState}
     * 对应的上一个状态的副作用已经真正完成之后才调用这个方法——这里本身不做任何时序校验，
     * 时序正确性是调用方的职责，这里只管把这次推进原子落库，同时清空 {@code errorMsg}
     * （上一次失败的痕迹不该跟着一次成功的推进继续挂着）。
     */
    void advance(long id, PptState newState, PptGenerationContext context);

    /** 某个状态执行失败：{@code status} 保持是这个失败的状态本身（下次重新跑它整个状态），只更新 errorMsg。 */
    void markFailed(long id, PptState failedState, String errorMsg);

    /** 请求取消：只写标记，由状态机在下一个状态边界完成终态切换。 */
    void requestCancel(long id);

    /** 在状态边界把任务标记为取消完成。 */
    void markCancelled(long id, PptState atState);

    boolean isCancelRequested(long id);

    /** 返回指定用户当前仍可继续推进的任务，匿名用户不应调用此查询。 */
    List<Long> runningTaskIdsFor(String userId);
}
