package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptContentSlideFill;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptPythonRenderer;
import com.agenttrail.capability.ppt.PptSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #33 验收标准第 3、4 条："生成的素材能正确嵌入到 PPT 的 Schema/渲染流程里"、
 * "用真实渲染跑通一次，产出的 PPT 里能看到生成的素材"——不 mock 任何一环：真实
 * {@link RenderStrategy} → 真实 {@link PptPythonRenderer} 子进程 → 真实
 * {@code render_ppt.py}（含新增的 {@code ppt_asset_generator.py} 装饰图形生成），
 * 再用 python-pptx 重新打开产物文件，数每一张幻灯片上真实存在的图片 shape 数量——
 * 不是"渲染没报错就算数"，是真的看见图片落进了文件里。
 *
 * <p>两个场景对应 issue #33/#31 两条链路共用的同一套图片嵌入机制（见
 * {@link com.agenttrail.capability.ppt.PptRenderPayload} 类注释）：
 * <ul>
 *   <li>{@code coverImageUrl} 为 null——纯 issue #33 场景，标题页和内容页全部退化成
 *   {@code ppt_asset_generator} 生成的装饰性图形；</li>
 *   <li>{@code coverImageUrl} 指向一个真实可下载的文件——验证 issue #31 那个此前从未被
 *   实际渲染消费过的字段，现在真的会被下载并贴到标题页上（用本地文件的 {@code file://} URL
 *   代替真实 MinIO URL，不依赖网络/MinIO 是否起着，{@code render_ppt.py} 里 file:// 和
 *   http(s):// 走的是同一段 urllib 下载代码，覆盖同一条代码路径）。</li>
 * </ul>
 */
class RenderStrategyAssetGenerationTest {

    @Test
    void generatesDecorativeAccentGraphicsOnEveryPageWhenThereIsNoCoverImage(@TempDir Path tempDir)
            throws Exception {
        PptSchema schema = new PptSchema("装饰性素材验收标题", "副标题在这里",
                List.of(
                        new PptContentSlideFill("第一张内容页标题", "第一张内容页正文"),
                        new PptContentSlideFill("第二张内容页标题", "第二张内容页正文")));
        // 三个字段构造函数默认 coverImageUrl 为 null——模拟 IMAGE 状态没配图/降级的情况

        Path outputFile = render(schema, tempDir);

        Map<String, String> pictureCounts = readBackPictureCountsPerSlide(outputFile);
        assertThat(pictureCounts.get("slide0_pictures"))
                .as("标题页没有 coverImageUrl 时也必须退化出一张 Pillow 装饰图形，不能是空白封面")
                .isEqualTo("1");
        assertThat(pictureCounts.get("slide1_pictures"))
                .as("第一张内容页必须有装饰图形").isEqualTo("1");
        assertThat(pictureCounts.get("slide2_pictures"))
                .as("复制出来的第二张内容页也必须各自有一张装饰图形，不能因为 duplicate_slide "
                        + "而漏贴、也不能因为复制时机不对而继承前一张的图片（曾经踩过的 bug）")
                .isEqualTo("1");
    }

    @Test
    void embedsTheRealCoverImageOnTheTitleSlideWhenCoverImageUrlIsPresent(@TempDir Path tempDir) throws Exception {
        Path fakeMinioCoverImage = tempDir.resolve("fake-cover.png");
        writeTinyPng(fakeMinioCoverImage);
        String fileUrlStandingInForMinioUrl = fakeMinioCoverImage.toUri().toString();

        PptSchema schema = new PptSchema("有封面图的标题", "副标题",
                List.of(new PptContentSlideFill("唯一一张内容页", "正文")),
                fileUrlStandingInForMinioUrl);

        Path outputFile = render(schema, tempDir);

        Map<String, String> pictureCounts = readBackPictureCountsPerSlide(outputFile);
        assertThat(pictureCounts.get("slide0_pictures"))
                .as("有 coverImageUrl 时标题页应该正好贴一张图（真实封面图，不是装饰图形兜底）")
                .isEqualTo("1");
        assertThat(pictureCounts.get("slide1_pictures")).isEqualTo("1");
    }

    private static Path render(PptSchema schema, Path tempDir) throws Exception {
        String templatePath = new ClassPathResource("ppt-templates/default-template.pptx")
                .getFile().getAbsolutePath();
        String renderScript = new ClassPathResource("ppt-scripts/render_ppt.py").getFile().getAbsolutePath();
        PptPythonRenderer renderer = new PptPythonRenderer("python", renderScript, 30);
        RenderStrategy strategy = new RenderStrategy(renderer, tempDir.resolve("out").toString());

        PptGenerationContext context = PptGenerationContext
                .initial("render-strategy-asset-test-" + System.nanoTime(), "unused")
                .withTemplatePath(templatePath)
                .withSchema(schema);

        PptGenerationContext result = strategy.execute(context);
        Path outputFile = Path.of(result.outputPath());
        assertThat(outputFile).as("RENDER 状态跑完必须真的产出一个文件").exists();
        return outputFile;
    }

    /** 真实调用 Java 内置的 ImageIO 写一张最小的 PNG——不依赖 Python/Pillow，纯粹是测试夹具。 */
    private static void writeTinyPng(Path pngFile) throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", new File(pngFile.toUri()));
    }

    private static Map<String, String> readBackPictureCountsPerSlide(Path pptxFile) throws Exception {
        String verifyScript = new ClassPathResource("ppt-scripts/verify_asset_images.py")
                .getFile().getAbsolutePath();
        ProcessBuilder processBuilder = new ProcessBuilder("python", verifyScript, pptxFile.toString())
                .redirectErrorStream(true);
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
