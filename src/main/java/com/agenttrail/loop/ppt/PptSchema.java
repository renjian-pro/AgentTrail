package com.agenttrail.loop.ppt;

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
 */
public record PptSchema(String titleText, String subtitleText, List<PptContentSlideFill> contentSlides) {
}
