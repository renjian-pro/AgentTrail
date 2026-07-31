package com.agenttrail.loop.ppt;

/**
 * PPT 生成状态机的 7 个状态（issue #24，路线图 Phase 6）：
 * {@code INIT→REQUIREMENT→SEARCH→TEMPLATE→OUTLINE→SCHEMA→RENDER→SUCCESS}。
 *
 * <p>顺序固定、单向推进，不支持跳转/回退——{@link PptGenerationService} 里的
 * {@code ORDER} 列表就是这条链路本身，新增状态时只改那一处。
 */
public enum PptState {
    INIT,
    REQUIREMENT,
    SEARCH,
    TEMPLATE,
    OUTLINE,
    SCHEMA,
    RENDER,
    SUCCESS
}
