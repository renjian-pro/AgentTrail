package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * 渲染载荷里一张内容页要填的字段列表（issue #24）——{@code render_ppt.py} 收到几项
 * {@code contentSlides} 就渲染几张内容页：第一项直接填模板第 1 张幻灯片，之后每一项先复制
 * 一份（{@code duplicate_slide}）再填。
 */
public record PptContentSlidePayload(List<PptTextFill> fills) {
}
