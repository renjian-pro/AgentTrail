package com.agenttrail.capability.ppt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只测 {@link PptPythonRenderer} 这一层"起子进程、等它结束、翻译退出码"的机制本身——用真实
 * python 解释器跑几个不依赖 python-pptx 的固定小脚本（{@code src/test/resources/ppt-scripts}），
 * 不是 mock：这是真实的 {@link ProcessBuilder}/{@code waitFor} 行为，只是不需要真实模板/真实
 * schema 就能快速、确定性地验证成功/失败/超时三条路径。真正调用 {@code render_ppt.py} 渲染出
 * 一份可打开 pptx 的完整链路见 {@code PptGenerationServiceIT}。
 */
class PptPythonRendererTest {

    private static String scriptPath(String name) throws IOException {
        return new ClassPathResource("ppt-scripts/" + name).getFile().getAbsolutePath();
    }

    @Test
    void succeedsAndProducesTheOutputFileWhenTheScriptExitsZero(@TempDir Path tempDir) throws Exception {
        PptPythonRenderer renderer = new PptPythonRenderer("python", scriptPath("ok_script.py"), 10);
        Path schema = tempDir.resolve("schema.json");
        Files.writeString(schema, "{}");
        Path output = tempDir.resolve("output.pptx");

        renderer.render("unused-template.pptx", schema, output);

        assertThat(output).exists();
        assertThat(Files.readString(output)).isEqualTo("rendered");
    }

    @Test
    void translatesANonZeroExitCodeIntoAReadableException(@TempDir Path tempDir) throws Exception {
        PptPythonRenderer renderer = new PptPythonRenderer("python", scriptPath("fail_script.py"), 10);
        Path schema = tempDir.resolve("schema.json");
        Files.writeString(schema, "{}");
        Path output = tempDir.resolve("output.pptx");

        assertThatThrownBy(() -> renderer.render("unused-template.pptx", schema, output))
                .isInstanceOf(PptRenderException.class)
                .hasMessageContaining("退出码")
                .hasMessageContaining("simulated render failure");
        assertThat(output).doesNotExist();
    }

    @Test
    void killsAndReportsATimeoutInsteadOfHangingForever(@TempDir Path tempDir) throws Exception {
        // waitFor(timeout, unit) 本身就是这里要验证的机制：脚本睡 30s，超时给 2s，
        // 断言几秒内就抛出而不是傻等 30s——如果这里改回"轮询 exitValue+sleep"的忙等写法，
        // 这个测试依然能通过，但那不是这个类要做的事，机制上的区别靠代码走查和类注释保证
        PptPythonRenderer renderer = new PptPythonRenderer("python", scriptPath("sleep_script.py"), 2);
        Path schema = tempDir.resolve("schema.json");
        Files.writeString(schema, "{}");
        Path output = tempDir.resolve("output.pptx");

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> renderer.render("unused-template.pptx", schema, output))
                .isInstanceOf(PptRenderException.class)
                .hasMessageContaining("超过");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).as("应该在超时附近就返回，不是等脚本自己跑完 30s").isLessThan(20_000);
    }

    @Test
    void reportsAClearErrorWhenThePythonExecutableItselfCannotBeStarted(@TempDir Path tempDir) throws Exception {
        PptPythonRenderer renderer = new PptPythonRenderer(
                "definitely-not-a-real-executable-xyz", scriptPath("ok_script.py"), 10);
        Path schema = tempDir.resolve("schema.json");
        Files.writeString(schema, "{}");
        Path output = tempDir.resolve("output.pptx");

        assertThatThrownBy(() -> renderer.render("unused-template.pptx", schema, output))
                .isInstanceOf(PptRenderException.class)
                .hasMessageContaining("启动 Python 渲染进程失败");
    }
}
