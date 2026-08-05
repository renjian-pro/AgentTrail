package com.agenttrail.loop.ppt;

/**
 * PPT 生成状态机的 8 个状态（issue #24 骨架 + issue #31 新增 IMAGE）：
 * {@code INIT→REQUIREMENT→SEARCH→TEMPLATE→OUTLINE→SCHEMA→IMAGE→RENDER→SUCCESS}。
 *
 * <p>顺序固定、单向推进，不支持跳转/回退——{@link PptGenerationService} 里的
 * {@code ORDER} 列表就是这条链路本身，新增状态时只改那一处。
 *
 * <p>{@code IMAGE} 排在 {@code SCHEMA} 之后、{@code RENDER} 之前（issue #31）：配图 prompt 用得上
 * SCHEMA 产出的标题/主题信息，而 RENDER 只是把 SCHEMA（此时已经带上 IMAGE 状态写回的
 * {@code coverImageUrl}）翻译成渲染用的机械结构，先后顺序上必须排在 RENDER 之前。
 */
public enum PptState {
    INIT,
    REQUIREMENT,
    SEARCH,
    TEMPLATE,
    OUTLINE,
    SCHEMA,
    IMAGE,
    RENDER,
    SUCCESS,
    /** 由用户取消，终态，不参与正常状态推进。 */
    CANCELLED
}
