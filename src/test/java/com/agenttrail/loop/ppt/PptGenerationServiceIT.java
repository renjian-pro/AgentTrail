package com.agenttrail.loop.ppt;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #24 验收标准："7 个状态端到端跑通一次、真的产出一个 pptx 文件"——真实 MySQL（见
 * {@link SharedMySql}）+ 真实 {@code deepseek-chat} 模型调用（REQUIREMENT/OUTLINE/SCHEMA
 * 三次真实结构化输出）+ 真实 Python 子进程渲染（{@code render_ppt.py} + 真实模板文件），
 * 不 mock 任何一环。
 *
 * <p>SEARCH 状态从 issue #29 起也是真实调用（{@link com.agenttrail.loop.ppt.strategy.SearchStrategy}
 * 真的会打 Tavily 联网搜索，不再是 issue #24 骨架阶段的 canned 输入），所以这份端到端测试
 * 现在顺带覆盖了真实联网搜索这一段；专门验证"SEARCH 收集到的素材真的流入 OUTLINE"这件事本身
 * 的更精确断言见 {@code SearchStrategyIT}。
 */
@SpringBootTest
class PptGenerationServiceIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private PptGenerationService pptGenerationService;

    @Test
    void runsAllSevenStatesEndToEndAndProducesAnOpenablePptxFile() throws Exception {
        long taskId = pptGenerationService.create("it-conv-" + System.currentTimeMillis(),
                "帮我做一份 3 页左右的 PPT，介绍一下为什么这个项目选择手写 ReAct Loop 而不是直接用"
                        + "Spring AI 的 ChatClient，以及 PPT 生成功能为什么最终选择了模板填充这条技术路线");

        PptTask task = pptGenerationService.describe(taskId).orElseThrow();
        assertThat(task.status())
                .as("失败时 errorMsg 是: " + task.errorMsg())
                .isEqualTo(PptState.SUCCESS);
        assertThat(task.errorMsg()).isNull();

        String outputPath = pptGenerationService.outputPathOf(taskId);
        assertThat(outputPath).isNotBlank();
        Path pptxFile = Path.of(outputPath);
        assertThat(pptxFile).exists();
        assertThat(Files.size(pptxFile)).as("真正渲染出内容的 pptx 不该比空模板小").isGreaterThan(20_000);

        // .pptx 本质是一个 ZIP 容器，magic bytes 校验是最基础的"这是一个合法 Office 文件"检查
        byte[] header = Files.readAllBytes(pptxFile);
        assertThat(header[0]).isEqualTo((byte) 'P');
        assertThat(header[1]).isEqualTo((byte) 'K');

        // 更强的证据：真的用 python-pptx 重新打开这份文件，读回标题文字——证明它不只是"看起来
        // 像"一个 pptx，而是选型报告里明确写"要用 python-pptx 打开"的那个库真的能重新解析它
        verifyOpenableWithPythonPptx(pptxFile);
    }

    private void verifyOpenableWithPythonPptx(Path pptxFile) throws IOException, InterruptedException {
        String verifyScript = new ClassPathResource("ppt-scripts/verify_openable.py").getFile().getAbsolutePath();
        Process process = new ProcessBuilder("python", verifyScript, pptxFile.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("python-pptx 重新打开产物文件超时").isTrue();
        assertThat(process.exitValue())
                .as("python-pptx 重新打开产物文件失败，输出: " + output)
                .isEqualTo(0);
        assertThat(output).contains("slide_count>=2");
    }
}
