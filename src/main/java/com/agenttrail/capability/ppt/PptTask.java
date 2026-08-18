package com.agenttrail.capability.ppt;

/**
 * 一条 PPT 生成任务的持久化记录（issue #24）。{@code status} 与 {@code contextJson} 组成当前
 * 业务 checkpoint，{@code runStatus} 记录运行生命周期，{@code revision} 保护条件提交；三者
 * 共同支撑按状态粒度的断点续传，而不是依赖 errorMsg 猜测任务是否仍在运行。
 *
 * @param status      当前状态：任务已经成功推进到这里、还没跑（新建时是 {@link PptState#INIT}），
 *                    或者上一次就是卡在这个状态失败了（这种情况下 {@code errorMsg} 非空）
 * @param errorMsg    {@code status} 对应状态上一次执行失败的错误信息；成功推进到这个状态时为
 *                    {@code null}（{@link PptTaskStore#advance} 会清空它）
 * @param runStatus   与业务状态分离的排队、运行、等待输入、失败、取消或成功生命周期
 * @param contextJson {@link PptGenerationContext} 的完整序列化快照——不是天然扁平的记录（嵌套
 *                    的 outline/schema 结构），整体存一段 JSON，参照 {@code agent_pause_state}
 *                    表已经验证过的取舍（拆列存储没有额外的查询收益）
 * @param contextVersion 快照格式版本；读取旧数据时由上下文兼容逻辑补齐
 * @param revision    单调递增版本，条件推进必须同时匹配 expected 状态和 revision
 */
public record PptTask(long id, String userId, String conversationId, PptState status, PptRunStatus runStatus,
                       String errorMsg, String contextJson, int contextVersion, long revision,
                       long createdAtMillis, long updatedAtMillis) {

    /** 旧调用方构造的任务仍按尚未入队处理，版本与 revision 使用安全初始值。 */
    public PptTask(long id, String conversationId, PptState status, String errorMsg, String contextJson,
            long createdAtMillis, long updatedAtMillis) {
        this(id, null, conversationId, status, PptRunStatus.QUEUED, errorMsg, contextJson,
                PptGenerationContext.CURRENT_CONTEXT_VERSION, 0, createdAtMillis, updatedAtMillis);
    }

    /** 兼容带 userId 的旧调用方。 */
    public PptTask(long id, String userId, String conversationId, PptState status, String errorMsg,
            String contextJson, long createdAtMillis, long updatedAtMillis) {
        this(id, userId, conversationId, status, PptRunStatus.QUEUED, errorMsg, contextJson,
                PptGenerationContext.CURRENT_CONTEXT_VERSION, 0, createdAtMillis, updatedAtMillis);
    }
}
