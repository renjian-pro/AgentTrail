package com.agenttrail.capability.ppt;

/**
 * {@code default-template.pptx}（issue #24）这一份具体模板的 shape 契约，SCHEMA 生成的 Prompt
 * 和 RENDER 状态翻译成渲染载荷时共用同一套名字/字数上限，避免 Java 两处各写一份、以后改模板
 * 漏改一处。
 *
 * <p>默认模板仍由这些常量提供兼容契约；可注册模板通过 {@link PptTemplateVersion} 携带自己的
 * shape/schema 元数据，不能在恢复时根据目录里的“最新文件”重新猜测映射。
 *
 * <p>字数上限只是 SCHEMA 阶段 Prompt 里给模型的软约束（模型不一定严格遵守，见踩坑点 #53），
 * 真正兜底截断发生在 {@code render_ppt.py} 里，这里的常量同时喂给两边，不能只改一边。
 */
public final class PptTemplateSpec {

    public static final String TITLE_SHAPE = "title_text";
    public static final String SUBTITLE_SHAPE = "subtitle_text";
    public static final String CONTENT_TITLE_SHAPE = "slide_title_text";
    public static final String CONTENT_BODY_SHAPE = "slide_body_text";

    public static final int TITLE_FONT_LIMIT = 30;
    public static final int SUBTITLE_FONT_LIMIT = 60;
    public static final int CONTENT_TITLE_FONT_LIMIT = 30;
    public static final int CONTENT_BODY_FONT_LIMIT = 400;

    private PptTemplateSpec() {
    }
}
