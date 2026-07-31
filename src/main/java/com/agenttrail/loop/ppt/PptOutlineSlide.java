package com.agenttrail.loop.ppt;

import java.util.List;

/** OUTLINE 状态里单张内容页的规划：一个标题 + 若干要点（issue #24）。 */
public record PptOutlineSlide(String title, List<String> bullets) {
}
