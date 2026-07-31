package com.agenttrail.loop.ppt;

/**
 * 渲染载荷里的最小单元（issue #24）：定位模板里一个 shape name，把文字填进去，字数超过
 * {@code fontLimit} 时渲染脚本会硬性截断（{@code render_ppt.py} 的 {@code apply_fill}，
 * 踩坑点 #53）。
 *
 * <p>这是 Java 和 Python 两侧都认识的机械化格式——和面向模型的 {@link PptSchema} 不同，
 * 这里不含任何"标题/副标题/正文"这类语义信息，只有"这个名字的 shape 该填什么、上限多少"，
 * 由 {@code strategy.RenderStrategy} 负责把 {@link PptSchema} 翻译成这个格式。
 */
public record PptTextFill(String shapeName, String text, int fontLimit) {
}
