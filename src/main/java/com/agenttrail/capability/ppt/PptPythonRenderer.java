package com.agenttrail.capability.ppt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 用 {@link ProcessBuilder} 起 Python 渲染脚本的具体实现（issue #24）——Java 侧只管流程编排，
 * 真正的模板填充逻辑在 {@code render_ppt.py} 里，这里负责把命令拼对、等它跑完、把非零退出码
 * 翻译成可读的失败原因。
 *
 * <p><b>必须用 {@link Process#waitFor(long, TimeUnit)}，不用忙等轮询</b>：issue #24 明确指出
 * 参考实现里有一处反面案例——用"轮询 {@code exitValue()}，抓 {@code IllegalThreadStateException}
 * 判断还没结束，再 {@code Thread.sleep()}"的写法等子进程结束，这既浪费 CPU 又让超时逻辑难写对。
 * {@code waitFor(timeout, unit)} 本身就是阻塞等待+超时双语义，不需要手写轮询循环。
 *
 * <p>两路输出流（stdout/stderr）在调用 {@code waitFor} 之前就用独立线程开始排空——如果脚本
 * 输出量超过操作系统管道缓冲区，子进程会阻塞在写入上，父进程如果这时候还没开始读，就会出现
 * 子进程等父进程读、父进程在 {@code waitFor} 里等子进程退出的相互卡死。
 */
public class PptPythonRenderer {

    private final String pythonExecutable;
    private final String renderScriptPath;
    private final long timeoutSeconds;

    public PptPythonRenderer(String pythonExecutable, String renderScriptPath, long timeoutSeconds) {
        this.pythonExecutable = pythonExecutable;
        this.renderScriptPath = renderScriptPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 同步调用，跑完/超时/失败才返回；成功时 {@code outputFile} 处一定存在一个真实文件。 */
    public void render(String templatePath, Path schemaJsonFile, Path outputFile) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExecutable, renderScriptPath,
                "--template", templatePath,
                "--schema", schemaJsonFile.toString(),
                "--output", outputFile.toString());

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException startFailed) {
            throw new PptRenderException("启动 Python 渲染进程失败（可执行文件: " + pythonExecutable
                    + "，脚本: " + renderScriptPath + "）: " + startFailed.getMessage(), startFailed);
        }

        StreamDrain stdout = StreamDrain.start(process.getInputStream());
        StreamDrain stderr = StreamDrain.start(process.getErrorStream());

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new PptRenderException("等待 Python 渲染进程时被中断", interrupted);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new PptRenderException("Python 渲染进程超过 " + timeoutSeconds + "s 未结束，已强制终止");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new PptRenderException("Python 渲染进程退出码 " + exitCode + "，stderr: " + stderr.await());
        }
        if (!Files.exists(outputFile)) {
            throw new PptRenderException("Python 渲染进程退出码 0 但没有生成输出文件: " + outputFile
                    + "，stdout: " + stdout.await());
        }
    }

    private static final class StreamDrain {
        private final Thread thread;
        private volatile String content = "";

        private StreamDrain(InputStream in) {
            this.thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // 进程被强制终止时读取会失败，这不是渲染失败的根因，吞掉即可，
                    // await() 返回目前已经读到的内容（可能是空串）
                }
            });
        }

        static StreamDrain start(InputStream in) {
            StreamDrain drain = new StreamDrain(in);
            drain.thread.start();
            return drain;
        }

        String await() {
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return content;
        }
    }
}
