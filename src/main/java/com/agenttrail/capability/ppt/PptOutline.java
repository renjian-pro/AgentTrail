package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * OUTLINE 状态的产出（issue #24）：内容规划，和模板无关——不知道、也不关心最终会填进哪个
 * shape。模板相关的映射是 SCHEMA 状态（{@link PptSchema}）的职责，两个状态故意分开：
 * OUTLINE 回答"讲什么"，SCHEMA 回答"怎么摆进这份模板"。
 */
public record PptOutline(String deckTitle, String deckSubtitle, List<PptOutlineSlide> slides) {
}
