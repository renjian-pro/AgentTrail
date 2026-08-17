package com.agenttrail.loop.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code toolParams} 这条不可见通道的读取口径。
 *
 * <h2>这组用例真正守的是暂停恢复那条路径</h2>
 *
 * 正常请求里 toolParams 是刚在内存里拼出来的，类型当然对。但**暂停恢复时它是从
 * {@code PauseStateJson} 反序列化回来的**——布尔可能变成 {@code "true"}，数字可能回来成
 * {@code Integer} 甚至字符串。
 *
 * <p>这正是 issue #96 修过的故障形状（被中断的分析会话恢复后拿到普通聊天执行器），
 * 而 issue #110 加 fileIds 时最初只认 {@code Number}，等于在同一条通道上重新埋了一遍：
 * 恢复之后这一轮的附件会静默消失，不报错、不留痕。两个读取方的宽容度必须一致。
 */
class ToolParamsTest {

    @Test
    @DisplayName("布尔开关同时认 true 和字符串 \"true\"——恢复路径靠这份宽容")
    void readsFlagsWrittenEitherAsBooleanOrAsJsonString() {
        assertThat(ToolParams.flag(Map.of("k", true), "k")).isTrue();
        assertThat(ToolParams.flag(Map.of("k", "true"), "k")).isTrue();
        assertThat(ToolParams.flag(Map.of("k", "TRUE"), "k")).isTrue();
        assertThat(ToolParams.flag(Map.of("k", false), "k")).isFalse();
        assertThat(ToolParams.flag(Map.of(), "k")).isFalse();
        assertThat(ToolParams.flag(null, "k")).isFalse();
    }

    /** <b>和 flag 一样的宽容度</b>——这是本类存在的全部理由。 */
    @Test
    @DisplayName("id 列表认 Integer / Long / 字符串，JSON 往返之后不会静默丢件")
    void readsIdListsRegardlessOfHowJsonRoundTrippedTheNumbers() {
        assertThat(ToolParams.longList(Map.of("k", List.of(1, 2)), "k")).containsExactly(1L, 2L);
        assertThat(ToolParams.longList(Map.of("k", List.of(1L, 2L)), "k")).containsExactly(1L, 2L);
        assertThat(ToolParams.longList(Map.of("k", List.of("12", " 13 ")), "k"))
                .as("恢复路径上数字可能回来成字符串，丢了就是「附件不见了」且毫无提示")
                .containsExactly(12L, 13L);
    }

    @Test
    @DisplayName("键不存在＝这一轮没带，不是错误")
    void treatsAMissingKeyAsAnEmptyList() {
        assertThat(ToolParams.longList(Map.of(), ToolParams.FILE_IDS)).isEmpty();
        assertThat(ToolParams.longList(null, ToolParams.FILE_IDS)).isEmpty();
        assertThat(ToolParams.longList(Map.of("k", "not-a-list"), "k")).isEmpty();
    }

    /** 单个元素解析不了就跳过它，不让整列作废——一个脏值不该导致其余附件全部消失。 */
    @Test
    @DisplayName("解析不了的单个元素被跳过，其余照常")
    void skipsUnparseableElementsInsteadOfDiscardingTheWholeList() {
        assertThat(ToolParams.longList(Map.of("k", java.util.Arrays.asList(1, "oops", 3)), "k"))
                .containsExactly(1L, 3L);
    }
}
