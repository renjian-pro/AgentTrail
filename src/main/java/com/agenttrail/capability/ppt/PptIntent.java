package com.agenttrail.capability.ppt;

/**
 * PPT 相关消息的意图分类（issue #24 骨架只做 {@link #CREATE}，issue #32 补齐 {@link #MODIFY}/
 * {@link #RESUME} 两个分支的处理路径，不只是识别）。
 *
 * <p>{@code RESUME} 这个意图分支和"崩溃恢复/断点续传"不是一回事，但底层复用的是同一套机制：
 * 后者（{@link PptGenerationService#run(long)}）天然具备"读当前状态、从这个状态继续"的能力，
 * 不需要用户显式说"继续"；{@code RESUME} 特指用户在对话里主动敲"继续生成"这类词时的意图识别
 * 分支（卡片上那颗"继续"按钮不走这里，它直接打带任务号的 {@code /resume} 端点）——识别出来之后，
 * {@link PptGenerationService} 按 conversationId 找到那条中断的任务，还是靠 {@code run(long)}
 * 接着跑，不是另外重新实现一套续传逻辑。{@code MODIFY}（对已生成的 PPT 做局部修改）识别出来后，
 * 定位到 conversationId 下最近一条已完成任务，复用其 REQUIREMENT/SEARCH/TEMPLATE/OUTLINE 产出，
 * 只从 SCHEMA 状态开始重新生成，不重新走 SEARCH/REQUIREMENT 这些前置状态。
 */
public enum PptIntent {
    CREATE,
    MODIFY,
    RESUME
}
