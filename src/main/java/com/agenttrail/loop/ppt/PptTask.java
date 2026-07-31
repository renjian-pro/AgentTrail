package com.agenttrail.loop.ppt;

/**
 * 一条 PPT 生成任务的持久化记录（issue #24）——DB 行的 {@code status}/{@code errorMsg} 就是
 * 断点续传的 checkpoint 本体（踩坑点 #44），按状态粒度，不是子步骤粒度。
 *
 * @param status      当前状态：任务已经成功推进到这里、还没跑（新建时是 {@link PptState#INIT}），
 *                    或者上一次就是卡在这个状态失败了（这种情况下 {@code errorMsg} 非空）
 * @param errorMsg    {@code status} 对应状态上一次执行失败的错误信息；成功推进到这个状态时为
 *                    {@code null}（{@link PptTaskStore#advance} 会清空它）
 * @param contextJson {@link PptGenerationContext} 的完整序列化快照——不是天然扁平的记录（嵌套
 *                    的 outline/schema 结构），整体存一段 JSON，参照 {@code agent_pause_state}
 *                    表已经验证过的取舍（拆列存储没有额外的查询收益）
 */
public record PptTask(long id, String conversationId, PptState status, String errorMsg, String contextJson,
                       long createdAtMillis, long updatedAtMillis) {
}
