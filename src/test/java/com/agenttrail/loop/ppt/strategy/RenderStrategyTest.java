package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.ppt.PptContentSlideFill;
import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptPythonRenderer;
import com.agenttrail.loop.ppt.PptSchema;
import com.agenttrail.loop.ppt.PptTemplateSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #30 验收标准第三条："模型给的内容超出 fontLimit"这个场景要有测试覆盖，且验证渲染出来的
 * 最终文件确实被截断——不是假设性通过。
 *
 * <p>{@code fontLimit} 只在 SCHEMA 状态的 Prompt 里作为软约束（{@link
 * com.agenttrail.loop.ppt.PptPrompts#SCHEMA}），模型不一定严格遵守（踩坑点 #53）；真正的硬性
 * 截断兜底在 {@code render_ppt.py} 的 {@code apply_fill} 里（issue #24 就已经实现，这里补的是
 * 之前缺失的测试）。这个测试直接构造一个"模型没遵守约束"的 {@link PptSchema}（字段长度故意超过
 * {@link PptTemplateSpec} 里的上限），走 {@link RenderStrategy} → 真实 {@link PptPythonRenderer}
 * 子进程 → 真实 {@code render_ppt.py}，再用 python-pptx 把产物文件重新打开、读回实际字符内容
 * 校验长度和内容——全链路都是真实的，唯一的测试替身是"手写一个超限的 PptSchema"代替真实 LLM
 * 调用，这一步本来就不该发起真实请求（这个场景要的是"给定模型输出超限"这个前提，不是"验证模型
 * 会不会超限"）。
 */
class RenderStrategyTest {

    @Test
    void truncatesFieldsThatExceedFontLimitInTheActualRenderedPptxFile(@TempDir Path tempDir) throws Exception {
        // 故意造一个"模型没有遵守 Prompt 里 fontLimit 软约束"的 Schema：titleText/slideBodyText
        // 都远超 PptTemplateSpec 里的上限，slideTitleText 则故意留在限制内，用来对照——证明截断
        // 是按字段各自的 fontLimit 精确命中，不是把所有文字无差别砍掉。
        String oversizedTitle = "T".repeat(PptTemplateSpec.TITLE_FONT_LIMIT + 20);
        String withinLimitSlideTitle = "小标题";
        String oversizedBody = "B".repeat(PptTemplateSpec.CONTENT_BODY_FONT_LIMIT + 100);
        PptSchema schema = new PptSchema(oversizedTitle, "副标题在限制内",
                List.of(new PptContentSlideFill(withinLimitSlideTitle, oversizedBody)));

        String templatePath = new ClassPathResource("ppt-templates/default-template.pptx")
                .getFile().getAbsolutePath();
        String renderScript = new ClassPathResource("ppt-scripts/render_ppt.py").getFile().getAbsolutePath();
        PptPythonRenderer renderer = new PptPythonRenderer("python", renderScript, 30);
        RenderStrategy strategy = new RenderStrategy(renderer, tempDir.toString());

        PptGenerationContext context = PptGenerationContext
                .initial("render-strategy-font-limit-test", "unused")
                .withTemplatePath(templatePath)
                .withSchema(schema);

        PptGenerationContext result = strategy.execute(context);

        Path outputFile = Path.of(result.outputPath());
        assertThat(outputFile).as("RENDER 状态跑完必须真的产出一个文件").exists();

        Map<String, String> actual = readBackRenderedShapeTexts(outputFile);

        // titleText 超限 20 个字符——真正落进产物文件里的必须精确等于截断到 TITLE_FONT_LIMIT
        // 之后的前缀，不能是原始的超限字符串（也就是模型输出没被兜底）
        assertThat(actual.get("title_len")).isEqualTo(String.valueOf(PptTemplateSpec.TITLE_FONT_LIMIT));
        assertThat(actual.get("title_text")).isEqualTo("T".repeat(PptTemplateSpec.TITLE_FONT_LIMIT));

        // slideBodyText 同理，截到 CONTENT_BODY_FONT_LIMIT
        assertThat(actual.get("slide_body_len")).isEqualTo(String.valueOf(PptTemplateSpec.CONTENT_BODY_FONT_LIMIT));
        assertThat(actual.get("slide_body_text")).isEqualTo("B".repeat(PptTemplateSpec.CONTENT_BODY_FONT_LIMIT));

        // 对照组：slideTitleText 本来就没超限，必须原样保留，证明截断是按字段各自判断，
        // 不是不管三七二十一把所有文本框都砍到某个固定长度
        assertThat(actual.get("slide_title_text")).isEqualTo(withinLimitSlideTitle);
    }

    /** 用真实 python-pptx 重新打开产物文件，读回三个 shape 的实际文字——不是假设，是真的解析文件。 */
    private static Map<String, String> readBackRenderedShapeTexts(Path pptxFile) throws Exception {
        String verifyScript = new ClassPathResource("ppt-scripts/verify_font_limit.py")
                .getFile().getAbsolutePath();
        ProcessBuilder processBuilder = new ProcessBuilder("python", verifyScript, pptxFile.toString())
                .redirectErrorStream(true);
        // 断言内容里含中文（"小标题"/"副标题在限制内"）——Windows 上子进程 stdout 默认走控制台
        // 代码页（通常是 GBK），不是 UTF-8，不强制 Python 侧的输出编码，读回来的中文会乱码，
        // 表现为断言失败但和截断逻辑本身无关，纯粹是测试脚手架的编码坑
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("python-pptx 重新打开产物文件超时").isTrue();
        assertThat(process.exitValue()).as("python-pptx 重新打开产物文件失败，输出: " + output).isEqualTo(0);

        Map<String, String> parsed = new java.util.HashMap<>();
        for (String line : output.split("\\r?\\n")) {
            int separatorIndex = line.indexOf('=');
            if (separatorIndex > 0) {
                parsed.put(line.substring(0, separatorIndex), line.substring(separatorIndex + 1));
            }
        }
        return parsed;
    }
}
