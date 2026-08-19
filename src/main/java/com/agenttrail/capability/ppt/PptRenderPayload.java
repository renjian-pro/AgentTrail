package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * {@code render_ppt.py} 读到的 schema JSON 的 Java 侧镜像（issue #24）——字段名和脚本里
 * {@code payload.get("titleSlideFills")}/{@code payload.get("contentSlides")} 一一对应，
 * 改任何一边字段名都要同步改另一边（没有共享的 schema 定义，这是这一票"最小骨架"接受的代价）。
 *
 * <p>{@code titleSlideImageUrl}（issue #33）：{@link PptSchema#coverImageUrl()} 在 issue #31
 * 落地时只是把 MinIO 稳定引用存进了 schema，从没真正翻译成渲染载荷——{@code render_ppt.py}
 * 之前完全不认识这个字段，标题页永远只有文字，没有图。这一票把这条链路补完：
 * {@code strategy.RenderStrategy} 在渲染时把 {@code coverImageUrl} 解析成短时可读 URL（为 {@code null} 时
 * {@code render_ppt.py} 退化成本票新增的 Pillow 装饰图形兜底，不留破损图片占位符），这样
 * "封面图怎么进渲染流程"和"内容页装饰图怎么进渲染流程"走的是同一套图片嵌入机制，不是各自
 * 发明一套。
 */
public record PptRenderPayload(List<TextFill> titleSlideFills, List<ContentSlide> contentSlides,
        String titleSlideImageUrl, List<DynamicPage> pages) {

    public PptRenderPayload {
        titleSlideFills = titleSlideFills == null ? List.of() : List.copyOf(titleSlideFills);
        contentSlides = contentSlides == null ? List.of() : List.copyOf(contentSlides);
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** 兼容 issue #24/#30 时期只有两个字段的调用点/测试——{@code titleSlideImageUrl} 默认为 null。 */
    public PptRenderPayload(List<TextFill> titleSlideFills, List<ContentSlide> contentSlides) {
        this(titleSlideFills, contentSlides, null, List.of());
    }

    public PptRenderPayload(List<TextFill> titleSlideFills, List<ContentSlide> contentSlides,
            String titleSlideImageUrl) {
        this(titleSlideFills, contentSlides, titleSlideImageUrl, List.of());
    }

    /** Java→Python 渲染边界的机械 DTO 只在本载荷内使用，收拢后避免五个只有一行字段的顶层文件。 */
    public record TextFill(String shapeName, String text, int fontLimit) { }

    public record ContentSlide(List<TextFill> fills) {
        public ContentSlide {
            fills = fills == null ? List.of() : List.copyOf(fills);
        }
    }

    public record DynamicPage(String pageId, String pageType, String templatePageRef,
            List<RenderField> fills, String speakerNotes) {
        public DynamicPage {
            fills = fills == null ? List.of() : List.copyOf(fills);
        }
    }

    public record RenderField(String shapeName, String type, String content, String url, Integer fontLimit) { }
}
