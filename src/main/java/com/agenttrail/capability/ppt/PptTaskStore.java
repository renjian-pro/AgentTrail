package com.agenttrail.capability.ppt;

import java.util.List;
import java.util.Optional;

/**
 * PPT 生成任务的存取接口（issue #24）。参照 {@code PauseStateStore}/{@code FileStore} 的既有
 * 模式：接口不假设存储介质，先有内存实现（快速单测用）+ JDBC 实现（生产用）。当前快照和
 * 追加式阶段事件分开存取：快照负责恢复，事件负责审计与进度展示。
 */
public interface PptTaskStore {

    /** 创建一条新任务，初始状态固定是 {@link PptState#INIT}，返回生成的主键。 */
    default long create(String conversationId, PptGenerationContext initialContext) {
        return create("legacy", conversationId, initialContext);
    }

    long create(String userId, String conversationId, PptGenerationContext initialContext);

    /**
     * 持久化创建幂等键与任务的绑定。JDBC 实现用唯一约束解决多实例竞态，内存实现只作为测试替身；
     * 没有幂等键时退化为普通创建。
     */
    default PptTaskCreation createIdempotent(String userId, String conversationId,
            PptGenerationContext initialContext, String scope, String idempotencyKey) {
        return new PptTaskCreation(create(userId, conversationId, initialContext), false);
    }

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
     * 查询会话下的全部任务，按创建顺序倒序返回，供历史版本卡片使用。
     * 默认实现兼容旧存储实现，但生产实现应在查询条件中直接带 userId，避免先读出他人任务再过滤。
     */
    default List<PptTask> findAllByConversationId(String conversationId) {
        return findLatestByConversationId(conversationId).stream().toList();
    }

    /** 只返回当前用户拥有的会话任务；conversationId 本身不是鉴权凭证。 */
    default List<PptTask> findAllByConversationId(String userId, String conversationId) {
        return findAllByConversationId(conversationId).stream()
                .filter(task -> userId == null || userId.equals(task.userId()))
                .toList();
    }

    /**
     * 兼容旧调用方的推进入口：先读取当前 checkpoint，再执行条件推进。
     * 新代码必须使用带 expected state/revision 的重载，避免迟到 worker 覆盖新快照。
     */
    default void advance(long id, PptState newState, PptGenerationContext context) {
        PptTask current = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PPT 任务不存在: " + id));
        boolean updated = conditionalAdvance(id, current.status(), current.revision(), newState,
                runStatusFor(newState), context);
        if (!updated) {
            throw new PptCheckpointConflictException(id, current.status(), current.revision());
        }
    }

    /** 仅当任务仍处于 expected state/revision 时推进，并追加不可覆盖的成功事件。 */
    boolean conditionalAdvance(long id, PptState expectedState, long expectedRevision,
            PptState newState, PptRunStatus newRunStatus, PptGenerationContext context);

    /** 抢占一个待运行/重试/人工继续任务；成功会递增 revision 并追加 STARTED 事件。 */
    boolean claim(long id, PptState expectedState, long expectedRevision);

    /** 兼容旧调用方的失败入口；失败仍停留在当前业务阶段，但生命周期进入 FAILED。 */
    default void markFailed(long id, PptState failedState, String errorMsg) {
        PptTask current = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PPT 任务不存在: " + id));
        PptFailure failure = new PptFailure("PPT_STAGE_FAILED", failedState, false,
                com.agenttrail.platform.error.RetryClass.FATAL,
                Math.max(current.attempt(), 1), errorMsg, System.currentTimeMillis());
        boolean updated = recordFailureIfCurrent(id, current.status(), current.revision(), failure,
                PptRunStatus.FAILED, 0);
        if (!updated) {
            throw new PptCheckpointConflictException(id, current.status(), current.revision());
        }
    }

    /** 仅当任务仍处于 expected state/revision 时记录结构化失败并追加失败事件。 */
    boolean recordFailureIfCurrent(long id, PptState expectedState, long expectedRevision,
            PptFailure failure, PptRunStatus nextRunStatus, long nextRetryAtMillis);

    /** 旧 API 保留为兼容入口；新执行器应使用 {@link #recordFailureIfCurrent}。 */
    default boolean markFailedIfCurrent(long id, PptState expectedState, long expectedRevision,
            PptState failedState, String errorMsg) {
        PptFailure failure = new PptFailure("PPT_STAGE_FAILED", failedState, false,
                com.agenttrail.platform.error.RetryClass.FATAL, 1, errorMsg, System.currentTimeMillis());
        return recordFailureIfCurrent(id, expectedState, expectedRevision, failure,
                PptRunStatus.FAILED, 0);
    }

    /** 返回任务的不可覆盖阶段事件，按写入顺序排列。 */
    List<PptCheckpointEvent> eventsForTask(long taskId);

    /** 追加阶段内部进度，不推进 checkpoint；用于图片 n/总数等可恢复的细节事件。 */
    void appendProgressEvent(long taskId, PptState stage, String outputSummary, String warningCode);

    /** 返回到期或租约异常后可由恢复扫描重新入队的任务，结果有界以避免启动洪峰。 */
    List<Long> recoverableTaskIds(long nowMillis, int limit);

    /** 请求取消：只写标记，由状态机在下一个状态边界完成终态切换。 */
    void requestCancel(long id);

    /** 在状态边界把任务标记为取消完成。 */
    void markCancelled(long id, PptState atState);

    boolean isCancelRequested(long id);

    /** 返回指定用户当前仍可继续推进的任务，匿名用户不应调用此查询。 */
    List<Long> runningTaskIdsFor(String userId);

    /** 运行状态和业务阶段的默认映射，供兼容入口使用。 */
    private static PptRunStatus runStatusFor(PptState state) {
        if (state == PptState.AWAITING_INPUT) {
            return PptRunStatus.WAITING_INPUT;
        }
        if (state == PptState.CANCELLED) {
            return PptRunStatus.CANCELLED;
        }
        if (state == PptState.SUCCESS) {
            return PptRunStatus.SUCCEEDED;
        }
        return PptRunStatus.RUNNING;
    }
}
