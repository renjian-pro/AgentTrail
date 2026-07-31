package com.agenttrail.loop.ppt;

/**
 * PPT 相关消息的意图分类（issue #24 验收范围只做 {@link #CREATE}）。
 *
 * <p>{@link #MODIFY}（对已生成的 PPT 做局部修改）和 {@link #RESUME}（用户主动要求继续跑一个
 * 之前中断的任务）是 issue #32 的范围——注意 {@code RESUME} 这个意图分支和"崩溃恢复/断点续传"
 * 不是一回事：后者由 {@link PptGenerationService#run(long)} 天然具备（每次都是"读当前状态、
 * 从这个状态继续"，不需要用户显式说"继续"），这里的 {@code RESUME} 特指用户在对话里主动敲
 * "继续生成"这类消息时的意图识别分支，本身还没实现。
 */
public enum PptIntent {
    CREATE,
    MODIFY,
    RESUME
}
