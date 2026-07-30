package com.agenttrail.loop.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 目录白名单沙箱的边界测试。
 *
 * <p>踩坑点 #41：一旦这些工具被包装成 MCP Server 对外暴露，"信任调用方"这个假设就不成立了，
 * 目录白名单是最低安全线。所以边界校验的每一条绕过路径都要单独有用例钉住。
 */
class PathSandboxTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");

    @Test
    void resolvesRelativePathsAgainstTheFirstAllowedDirectory(@TempDir Path root) {
        PathSandbox sandbox = PathSandbox.restrictedTo(root.toString());

        assertThat(sandbox.resolve("sub/file.txt"))
                .isEqualTo(root.resolve("sub").resolve("file.txt"));
    }

    /** 空路径解析成基准目录本身，方便 list_files 之类的工具不传参就列当前目录。 */
    @Test
    void resolvesBlankPathToTheBaseDirectory(@TempDir Path root) {
        PathSandbox sandbox = PathSandbox.restrictedTo(root.toString());

        assertThat(sandbox.resolve("  ")).isEqualTo(sandbox.baseDir());
    }

    /** 最典型的越权姿势：用 {@code ../} 从白名单目录里爬出去。 */
    @Test
    void rejectsPathTraversalOutOfTheWhitelist(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("root"));
        Files.writeString(parent.resolve("secret.txt"), "secret");
        PathSandbox sandbox = PathSandbox.restrictedTo(root.toString());

        assertThatExceptionOfType(SandboxViolationException.class)
                .isThrownBy(() -> sandbox.resolve("../secret.txt"))
                .withMessageContaining("secret.txt");
    }

    @Test
    void rejectsAbsolutePathsOutsideTheWhitelist(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("root"));
        Path outside = Files.writeString(parent.resolve("secret.txt"), "secret");
        PathSandbox sandbox = PathSandbox.restrictedTo(root.toString());

        assertThatExceptionOfType(SandboxViolationException.class)
                .isThrownBy(() -> sandbox.resolve(outside.toString()));
    }

    /**
     * 边界比较必须按路径分段比，不能按字符串前缀比：
     * {@code /data/root-evil} 的字符串前缀是 {@code /data/root}，但它显然是白名单之外的另一个目录。
     */
    @Test
    void rejectsSiblingDirectoryThatMerelySharesAStringPrefix(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("root"));
        Path lookalike = Files.createDirectory(parent.resolve("root-evil"));
        PathSandbox sandbox = PathSandbox.restrictedTo(root.toString());

        assertThatExceptionOfType(SandboxViolationException.class)
                .isThrownBy(() -> sandbox.resolve(lookalike.resolve("file.txt").toString()));
    }

    @Test
    void allowsEveryDirectoryInTheWhitelistNotJustTheFirst(@TempDir Path parent) throws IOException {
        Path first = Files.createDirectory(parent.resolve("first"));
        Path second = Files.createDirectory(parent.resolve("second"));
        PathSandbox sandbox = PathSandbox.restrictedTo(first.toString(), second.toString());

        // 不用 AssertJ 的 Path#startsWith 断言：它内部会做 toRealPath，而这个文件并不存在
        assertThat(sandbox.resolve(second.resolve("file.txt").toString()).startsWith(second)).isTrue();
        assertThat(sandbox.baseDir()).isEqualTo(first.toRealPath());
    }

    /** 不设白名单时不做边界校验——单机自用场景保留这个口子，但 MCP 暴露时必须显式设白名单（#41）。 */
    @Test
    void unrestrictedSandboxAllowsAnyPath(@TempDir Path anywhere) {
        PathSandbox sandbox = PathSandbox.unrestricted();

        assertThat(sandbox.resolve(anywhere.toString())).isEqualTo(anywhere.toAbsolutePath().normalize());
    }

    /**
     * 只做 {@code normalize()} 的字符串归一化挡不住符号链接：白名单里放一个指向外部的软链，
     * 归一化后的路径看起来还在白名单内，实际读到的是外部文件。所以校验前要先解析真实路径。
     *
     * <p>Windows 上建符号链接要开发者模式/管理员权限，退而求其次用目录联接（junction）——
     * 它同样是重解析点，同样能用来逃逸，而且不需要提权。两种都建不了才跳过。
     */
    @Test
    void rejectsEscapeThroughASymbolicLink(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("root"));
        Path outsideDir = Files.createDirectory(parent.resolve("outside"));
        Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        Path link = root.resolve("shortcut");

        assumeTrue(createLink(link, outsideDir), "当前环境不允许创建符号链接或目录联接，跳过");

        PathSandbox sandbox = PathSandbox.restrictedTo(root.toString());

        assertThatExceptionOfType(SandboxViolationException.class)
                .isThrownBy(() -> sandbox.resolve("shortcut/secret.txt"));
    }

    private static boolean createLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException noPermission) {
            return WINDOWS && createJunction(link, target);
        }
    }

    private static boolean createJunction(Path link, Path target) {
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c",
                    "mklink /J \"%s\" \"%s\"".formatted(link, target))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
