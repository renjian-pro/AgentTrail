package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * SCHEMA 状态的产出（issue #24）——把 {@link PptOutline} 的内容映射成"这份具体模板"要填的文字：
 * {@code titleText}/{@code subtitleText} 对应模板第 0 张幻灯片（{@code title_text}/
 * {@code subtitle_text}），{@code contentSlides} 每一项对应一张内容页（模板第 1 张幻灯片
 * 复制出来的一份，{@code slide_title_text}/{@code slide_body_text}）。
 *
 * <p>{@link com.agenttrail.loop.model.OutputType} 只支持单对象类型，{@code contentSlides}
 * 这个列表包在这个类里而不是单独结构化输出一个 {@code List}，是 {@code OutputType} 类注释里
 * 写明的既有约定（{@code DeepResearch} 的 {@code ResearchPlan} 同理）。
 *
 * <p>这里不是渲染侧真正认的 JSON 结构——渲染侧认的是按模板 shape name 组织的通用格式
 * （{@code PptRenderPayload}），由 {@code strategy.RenderStrategy} 负责把这个语义化的 Schema
 * 翻译成那个机械的、和模板强绑定的格式，两者故意分层。
 *
 * <p>{@code coverImageUrl}（issue #31）：SCHEMA 状态产出时这个字段总是 {@code null}——SCHEMA 状态
 * 本身是一次纯文本结构化输出的 LLM 调用，不知道配图这回事；紧随其后的 IMAGE 状态
 * （{@code strategy.ImageStrategy}）负责调文生图 API、立即下载转存 MinIO，再把这个字段补上
 * "一份新的 PptSchema"（record 不可变，"补上"意味着重新构造一份）。这里存的必须是 MinIO 的永久
 * URL，绝不能是文生图 API 直接返回的临时链接——过期后历史 PPT 引用的图就全部失效了，这正是
 * issue #31 要解决的问题。配图失败时这个字段保持 {@code null}，RENDER 状态不读这个字段，
 * 照常只用文字渲染，不因为配图失败连累整条流水线。
 */
public record PptSchema(String titleText, String subtitleText, List<PptContentSlideFill> contentSlides,
        String coverImageUrl) {

    /** 兼容 issue #24 时期只有三个字段的调用点——{@code coverImageUrl} 默认为 {@code null}（未配图）。 */
    public PptSchema(String titleText, String subtitleText, List<PptContentSlideFill> contentSlides) {
        this(titleText, subtitleText, contentSlides, null);
    }
}
