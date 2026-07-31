package com.agenttrail.loop.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPromptFormatterTest {

    @Test
    void emptyListProducesAnEmptyString() {
        assertThat(MemoryPromptFormatter.formatSection(List.of())).isEmpty();
        assertThat(MemoryPromptFormatter.formatSection(null)).isEmpty();
    }

    @Test
    void groupsItemsByTypeUnderChineseLabelsInEnumOrder() {
        String section = MemoryPromptFormatter.formatSection(List.of(
                new MemoryItem("user-1", MemoryType.FACT, "使用 MySQL 8.0", 1L),
                new MemoryItem("user-1", MemoryType.PROFILE, "产品经理", 1L),
                new MemoryItem("user-1", MemoryType.PROFILE, "擅长数据分析", 1L)));

        assertThat(section).isEqualTo("""
                # 长期记忆

                ## 用户画像
                - 产品经理
                - 擅长数据分析

                ## 已知事实
                - 使用 MySQL 8.0
                """);
    }
}
