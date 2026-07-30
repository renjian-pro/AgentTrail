package com.agenttrail.loop.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 伪持久 shell 的行为测试。
 *
 * <p>踩坑点 #20：这里不维护长活进程，只在会话里记一个 {@code lastDirectory}，
 * 每次执行前拼 {@code cd} 前缀，执行后再把真实工作目录回读出来。所以"目录状态跨调用保持"
 * 是这套设计唯一需要钉死的语义（本票验收标准）。
 *
 * <p>测试起的是真实 shell 进程（Windows 走 cmd.exe，其余走 bash），不打桩——
 * 打了桩就等于把这套机制里唯一有风险的部分（真实 shell 的解析行为）测没了。
 */
class ShellSessionManagerTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String SESSION = "test-session";

    @TempDir
    Path workspace;

    private ShellSessionManager manager() {
        return ShellSessionManager.builder()
                .initialDirectory(workspace.toString())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /** 打印当前目录：cmd.exe 里不带参数的 {@code cd} 就是 pwd。 */
    private static String printWorkingDirectory() {
        return WINDOWS ? "cd" : "pwd";
    }

    /** 打印一个文件的内容，用相对路径——只有当前目录正确时才能成功。 */
    private static String printFile(String name) {
        return (WINDOWS ? "type " : "cat ") + name;
    }

    /** 临时目录在部分系统上是通过软链访问的，比对前统一取真实路径。 */
    private static String real(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException unresolvable) {
            return path.toString();
        }
    }

    @Test
    void runsACommandInTheConfiguredInitialDirectory() {
        ShellSessionManager manager = manager();

        ShellSessionManager.CommandResult result = manager.execute(SESSION, printWorkingDirectory());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout().trim()).isEqualToIgnoringCase(real(workspace));
    }

    /** 本票验收标准：{@code cd} 之后，后续的每一次调用都应该落在新目录里。 */
    @Test
    void keepsTheWorkingDirectoryBetweenSeparateCalls() throws IOException {
        Path sub = Files.createDirectory(workspace.resolve("sub"));
        Files.writeString(sub.resolve("marker.txt"), "found-me");
        ShellSessionManager manager = manager();

        manager.execute(SESSION, "cd sub");
        ShellSessionManager.CommandResult afterCd = manager.execute(SESSION, printFile("marker.txt"));

        assertThat(manager.workingDirectory(SESSION)).isEqualToIgnoringCase(real(sub));
        assertThat(afterCd.stdout()).contains("found-me");
    }

    /**
     * 参考实现只在命令**以 {@code cd } 开头**时才更新目录状态，`mkdir x && cd x` 这类
     * 组合命令会让状态和真实目录脱节。这里改成执行后从 shell 里回读真实工作目录，
     * 不再猜命令的形状。
     */
    @Test
    void tracksDirectoryChangesHiddenInsideCompoundCommands() throws IOException {
        Files.createDirectory(workspace.resolve("sub"));
        ShellSessionManager manager = manager();

        manager.execute(SESSION, "cd sub && " + printWorkingDirectory());

        assertThat(manager.workingDirectory(SESSION))
                .isEqualToIgnoringCase(real(workspace.resolve("sub")));
    }

    /** {@code cd ..} 要回到父目录，而不是留下一个 {@code /a/b/..} 这样没归一化的路径。 */
    @Test
    void normalisesTheDirectoryWhenGoingUp() throws IOException {
        Files.createDirectory(workspace.resolve("sub"));
        ShellSessionManager manager = manager();

        manager.execute(SESSION, "cd sub");
        manager.execute(SESSION, "cd ..");

        assertThat(manager.workingDirectory(SESSION))
                .isEqualToIgnoringCase(real(workspace))
                .doesNotContain("..");
    }

    @Test
    void resetDropsTheSessionStateBackToTheInitialDirectory() throws IOException {
        Files.createDirectory(workspace.resolve("sub"));
        ShellSessionManager manager = manager();
        manager.execute(SESSION, "cd sub");

        manager.reset(SESSION);

        assertThat(manager.workingDirectory(SESSION)).isEqualToIgnoringCase(real(workspace));
    }

    /** 不同 sessionId 之间目录状态互不影响。 */
    @Test
    void isolatesDirectoryStateBetweenSessions() throws IOException {
        Files.createDirectory(workspace.resolve("sub"));
        ShellSessionManager manager = manager();

        manager.execute("session-a", "cd sub");

        assertThat(manager.workingDirectory("session-b")).isEqualToIgnoringCase(real(workspace));
    }

    @Test
    void reportsTheRealExitCodeOfTheCommand() {
        ShellSessionManager manager = manager();

        ShellSessionManager.CommandResult result = manager.execute(SESSION, "exit 3");

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void capturesStandardErrorSeparatelyFromStandardOutput() {
        ShellSessionManager manager = manager();

        ShellSessionManager.CommandResult result = manager.execute(SESSION, "echo to-stderr 1>&2");

        assertThat(result.stderr()).contains("to-stderr");
        assertThat(result.stdout()).doesNotContain("to-stderr");
    }

    /** 输出双向截断：stdout 有上限。 */
    @Test
    void truncatesOversizedStandardOutput() {
        ShellSessionManager manager = ShellSessionManager.builder()
                .initialDirectory(workspace.toString())
                .maxLines(2)
                .build();

        ShellSessionManager.CommandResult result =
                manager.execute(SESSION, "echo one && echo two && echo three && echo four");

        assertThat(result.stdout()).contains("截断").doesNotContain("four");
    }

    /** 输出双向截断：stderr 也必须有上限，否则一个刷屏的报错就能把上下文顶爆。 */
    @Test
    void truncatesOversizedStandardError() {
        ShellSessionManager manager = ShellSessionManager.builder()
                .initialDirectory(workspace.toString())
                .maxLines(2)
                .build();

        ShellSessionManager.CommandResult result = manager.execute(SESSION,
                "echo one 1>&2 && echo two 1>&2 && echo three 1>&2 && echo four 1>&2");

        assertThat(result.stderr()).contains("截断").doesNotContain("four");
    }

    /** 超时要杀掉子进程并如实标记，不能挂死整个 loop。 */
    @Test
    void killsAndFlagsCommandsThatExceedTheTimeout() {
        ShellSessionManager manager = ShellSessionManager.builder()
                .initialDirectory(workspace.toString())
                .timeout(Duration.ofMillis(700))
                .build();

        ShellSessionManager.CommandResult result = manager.execute(SESSION,
                WINDOWS ? "ping -n 10 127.0.0.1" : "sleep 10");

        assertThat(result.timedOut()).isTrue();
        assertThat(result.isSuccess()).isFalse();
    }

    /** 超时/失败都不该把会话目录带偏——回读不到目录时保持原状态。 */
    @Test
    void keepsTheDirectoryWhenACommandFails() {
        ShellSessionManager manager = manager();

        manager.execute(SESSION, "cd no-such-directory-here");

        assertThat(manager.workingDirectory(SESSION)).isEqualToIgnoringCase(real(workspace));
    }
}
