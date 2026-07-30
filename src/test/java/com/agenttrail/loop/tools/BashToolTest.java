package com.agenttrail.loop.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BashToolTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path workspace;

    private BashTool tool() {
        return BashTool.builder()
                .sessionManager(ShellSessionManager.builder()
                        .initialDirectory(workspace.toString())
                        .build())
                .build();
    }

    @Test
    void isExposedAsASingleToolCallbackNamedBash() {
        ToolCallback callback = tool().toolCallback();

        assertThat(callback.getToolDefinition().name()).isEqualTo("bash");
        assertThat(callback.getToolDefinition().inputSchema()).contains("command").contains("restart");
    }

    @Test
    void runsTheCommandGivenInTheJsonArguments() {
        String result = tool().toolCallback().call("{\"command\":\"echo hello-from-shell\"}");

        assertThat(result).contains("hello-from-shell");
    }

    /** 目录状态跨调用保持——从 ToolCallback 这一层看也必须成立。 */
    @Test
    void keepsTheWorkingDirectoryAcrossTwoToolCallbackInvocations() throws IOException {
        Path sub = Files.createDirectory(workspace.resolve("sub"));
        Files.writeString(sub.resolve("marker.txt"), "found-me");
        ToolCallback callback = tool().toolCallback();

        callback.call("{\"command\":\"cd sub\"}");
        String result = callback.call("{\"command\":\"" + (WINDOWS ? "type" : "cat") + " marker.txt\"}");

        assertThat(result).contains("found-me");
    }

    /** restart=true 把会话状态清掉，回到初始目录。 */
    @Test
    void restartResetsTheSessionDirectory() throws IOException {
        Files.createDirectory(workspace.resolve("sub"));
        BashTool tool = tool();
        tool.execute("cd sub", null, null);

        String result = tool.execute(WINDOWS ? "cd" : "pwd", true, null);

        assertThat(result).doesNotContain("sub");
    }

    @Test
    void reportsANonZeroExitCodeToTheModel() {
        String result = tool().execute("exit 7", null, null);

        assertThat(result).contains("7");
    }

    @Test
    void rejectsABlankCommandWithoutStartingAProcess() {
        assertThat(tool().toolCallback().call("{}")).startsWith("Error:");
    }

    /**
     * 参考实现的工具描述宣称"环境变量持久化"，但实现只保留了工作目录——又一个
     * 文档与实现不一致的例子（同 #17）。这里的描述只承诺目录持久化。
     */
    @Test
    void doesNotClaimEnvironmentVariablePersistenceItCannotDeliver() {
        String description = tool().toolCallback().getToolDefinition().description();

        assertThat(description).contains("工作目录").doesNotContain("环境变量持久");
    }
}
