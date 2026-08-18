package com.agenttrail.capability.ppt;

/**
 * PPT 生成状态机的状态（issue #24 骨架 + issue #31 新增 IMAGE + 需求澄清新增 CLARIFY/AWAITING_INPUT）：
 * {@code INIT→CLARIFY→REQUIREMENT→SEARCH→TEMPLATE→OUTLINE→SCHEMA→IMAGE→RENDER→SUCCESS}。
 *
 * <p>顺序固定、单向推进，不支持跳转/回退——{@link PptGenerationService} 里的
 * {@code ORDER} 列表就是这条链路本身，新增状态时只改那一处。
 *
 * <p>{@code IMAGE} 排在 {@code SCHEMA} 之后、{@code RENDER} 之前（issue #31）：配图 prompt 用得上
 * SCHEMA 产出的标题/主题信息，而 RENDER 只是把 SCHEMA（此时已经带上 IMAGE 状态写回的
 * {@code coverImageUrl}）翻译成渲染用的机械结构，先后顺序上必须排在 RENDER 之前。
 *
 * <p>{@code CLARIFY} 紧跟 {@code INIT}：它只做"信息够不够开始做"的判定，必须排在
 * {@code REQUIREMENT} 之前——{@code REQUIREMENT} 的提示词是"用户没说就合理推断一个默认值",
 * 一旦跑到那里，需求含糊这件事就被默认值悄悄抹平了，再没有追问的时机。
 */
public enum PptState {
    INIT,
    /** 需求清晰度判定（一次不挂工具的 LLM 调用），信息足够就直接推进到 {@link #REQUIREMENT}。 */
    CLARIFY,
    REQUIREMENT,
    SEARCH,
    TEMPLATE,
    OUTLINE,
    SCHEMA,
    IMAGE,
    RENDER,
    SUCCESS,
    /** 由用户取消，终态，不参与正常状态推进。 */
    CANCELLED,
    /**
     * {@code CLARIFY} 判定信息不足，任务停在这里等用户补充——**不是终态也不是失败态**，
     * 是唯一一个"要等人"的暂停态：{@code ORDER} 里没有它，也没有对应的 Strategy，
     * {@link PptGenerationService#run} 见到它就停下来（和 {@link #CANCELLED} 同一处判断）。
     *
     * <p>枚举名控制在 20 字符以内是硬约束：{@code ppt_generation_task.status} 是
     * {@code VARCHAR(20)}，超长会被静默截断成一个谁都认不出的状态名。
     */
    AWAITING_INPUT
}
