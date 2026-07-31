package com.agenttrail.loop.ppt;

import java.util.List;

/**
 * {@code render_ppt.py} 读到的 schema JSON 的 Java 侧镜像（issue #24）——字段名和脚本里
 * {@code payload.get("titleSlideFills")}/{@code payload.get("contentSlides")} 一一对应，
 * 改任何一边字段名都要同步改另一边（没有共享的 schema 定义，这是这一票"最小骨架"接受的代价）。
 */
public record PptRenderPayload(List<PptTextFill> titleSlideFills, List<PptContentSlidePayload> contentSlides) {
}
