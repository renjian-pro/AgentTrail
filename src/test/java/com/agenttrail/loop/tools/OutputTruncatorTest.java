package com.agenttrail.loop.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 输出截断：stdout / stderr 都要限长，且截断不能把多字节字符劈成两半。 */
class OutputTruncatorTest {

    @Test
    void leavesShortOutputUntouched() {
        OutputTruncator truncator = new OutputTruncator(10, 1000, StandardCharsets.UTF_8);

        assertThat(truncator.truncate("line-1\nline-2")).isEqualTo("line-1\nline-2");
    }

    @Test
    void truncatesByLineCount() {
        OutputTruncator truncator = new OutputTruncator(2, 1000, StandardCharsets.UTF_8);

        String truncated = truncator.truncate("a\nb\nc\nd");

        assertThat(truncated).startsWith("a\nb").contains("截断").doesNotContain("\nc");
    }

    /**
     * 字节上限要按"完整字符"截断。参考实现直接 {@code new String(bytes, 0, maxBytes, charset)}，
     * 会把最后一个多字节字符劈开，模型收到的是一个替换字符（U+FFFD）而不是原文。
     */
    @Test
    void truncatesByByteSizeWithoutSplittingMultiByteCharacters() {
        OutputTruncator truncator = new OutputTruncator(1000, 7, StandardCharsets.UTF_8);

        String truncated = truncator.truncate("中文中文中文");

        assertThat(truncated).startsWith("中文").doesNotContain("�").contains("截断");
    }

    @Test
    void returnsEmptyStringForNullOrEmptyInput() {
        OutputTruncator truncator = new OutputTruncator(10, 100, StandardCharsets.UTF_8);

        assertThat(truncator.truncate(null)).isEmpty();
        assertThat(truncator.truncate("")).isEmpty();
    }
}
