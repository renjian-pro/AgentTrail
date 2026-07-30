package com.agenttrail.loop.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grep 工具测试。
 *
 * <p>本票验收标准是"ripgrep 不可用时能正确退化"，所以 ripgrep 的可用性判定被做成了可注入的：
 * 否则这条路径只能在没装 ripgrep 的机器上测到，CI 换台机器结论就变了。
 */
class GrepToolTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void seedFiles() throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), """
                class App {
                    // TODO: 补充日志
                    void run() { logError("boom"); }
                }
                """);
        Files.writeString(workspace.resolve("src/notes.txt"), "TODO: 写文档\n无关内容\n");
    }

    /** 纯 Java 实现：ripgrep 判定为不可用时走这条路（验收标准）。 */
    private GrepTool javaOnly() {
        return GrepTool.builder()
                .allowedDirs(workspace.toString())
                .ripgrepAvailability(() -> false)
                .build();
    }

    @Test
    void usesTheJavaImplementationWhenRipgrepIsUnavailable() {
        GrepTool grep = javaOnly();

        assertThat(grep.usingRipgrep()).isFalse();
        assertThat(grep.grep("TODO", null, null, null, null, null, null, null, null))
                .contains("App.java")
                .contains("notes.txt");
    }

    /**
     * 退化不能只发生在构造期：ripgrep 判定为"可用"，但真去起进程时二进制不在了
     * （PATH 变了、容器里被裁掉了），也必须落回 Java 实现，而不是把 IOException 甩给模型。
     */
    @Test
    void fallsBackToJavaWhenTheRipgrepProcessCannotBeStarted() {
        GrepTool grep = GrepTool.builder()
                .allowedDirs(workspace.toString())
                .ripgrepAvailability(() -> true)
                .ripgrepExecutable("rg-that-does-not-exist-agenttrail")
                .build();

        assertThat(grep.usingRipgrep()).isTrue();

        String result = grep.grep("TODO", null, null, null, null, null, null, null, null);

        assertThat(result).contains("App.java");
        assertThat(grep.usingRipgrep()).as("退化之后不应该每次调用都再试一遍").isFalse();
    }

    @Test
    void filtersByGlob() {
        String result = javaOnly().grep("TODO", null, "*.java", null, null, null, null, null, null);

        assertThat(result).contains("App.java").doesNotContain("notes.txt");
    }

    @Test
    void listsOnlyFileNamesInFilesWithMatchesMode() {
        String result = javaOnly()
                .grep("TODO", null, "*.java", "files_with_matches", null, null, null, null, null);

        assertThat(result).contains("App.java").doesNotContain("TODO");
    }

    @Test
    void countsMatchesPerFileInCountMode() {
        String result = javaOnly().grep("TODO", null, "*.txt", "count", null, null, null, null, null);

        assertThat(result).contains("notes.txt").contains("1");
    }

    @Test
    void honoursIgnoreCase() {
        assertThat(javaOnly().grep("logerror", null, null, null, null, null, false, null, null))
                .isEqualTo("No matches found.");
        assertThat(javaOnly().grep("logerror", null, null, null, null, null, true, null, null))
                .contains("logError");
    }

    /**
     * 参考实现按"每个匹配各自输出一个上下文窗口"拼结果，两个相邻匹配的窗口重叠时
     * 同一行会被输出两遍。上下文窗口要先合并再输出。
     */
    @Test
    void doesNotRepeatLinesWhenContextWindowsOverlap() throws IOException {
        Files.writeString(workspace.resolve("dup.txt"), "a\nhit\nhit\nb\n");

        String result = javaOnly().grep("hit", "dup.txt", null, "content", 1, 1, null, null, null);

        // 不合并的话会输出 6 行（两个窗口 a,hit,hit + hit,hit,b），"hit" 结尾的行会有 4 条
        assertThat(result.lines().count()).isEqualTo(4);
        assertThat(result.lines().filter(line -> line.endsWith("hit")).count()).isEqualTo(2);
        assertThat(result.lines().filter(line -> line.endsWith("a")).count()).isEqualTo(1);
    }

    @Test
    void appliesHeadLimit() {
        String result = javaOnly().grep("TODO", null, null, "content", null, null, null, 1, null);

        assertThat(result.lines().count()).isEqualTo(1);
    }

    @Test
    void readsNonUtf8FilesThroughTheSameEncodingFallback() throws IOException {
        Files.write(workspace.resolve("gbk.txt"), "关键字在这里\n".getBytes(Charset.forName("GBK")));

        assertThat(javaOnly().grep("关键字", null, "*.txt", null, null, null, null, null, null))
                .contains("gbk.txt");
    }

    /** 二进制文件不该被当成文本搜出来，否则一个 jar 就能塞满上下文。 */
    @Test
    void skipsBinaryFiles() throws IOException {
        Files.write(workspace.resolve("blob.bin"), new byte[]{'h', 'i', 't', 0, 'h', 'i', 't'});

        assertThat(javaOnly().grep("hit", null, null, "files_with_matches", null, null, null, null, null))
                .doesNotContain("blob.bin");
    }

    /** 目录白名单对 grep 同样适用（#41）：搜索路径越界要被拒。 */
    @Test
    void refusesToSearchOutsideTheWhitelistedDirectory() {
        String result = javaOnly()
                .grep("TODO", workspace.getParent().toString(), null, null, null, null, null, null, null);

        assertThat(result).startsWith("Error:").contains("不在允许访问的目录内");
    }

    @Test
    void isExposedAsASingleToolCallbackNamedGrep() {
        assertThat(javaOnly().toolCallback().getToolDefinition().name()).isEqualTo("grep");
        assertThat(javaOnly().toolCallback().call("{\"pattern\":\"TODO\",\"glob\":\"*.java\"}"))
                .contains("App.java");
    }

    @Test
    void reportsAnInvalidRegexAsAToolErrorInsteadOfThrowing() {
        assertThat(javaOnly().grep("[unclosed", null, null, null, null, null, null, null, null))
                .startsWith("Error:");
    }
}
