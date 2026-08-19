package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptField;
import com.agenttrail.capability.ppt.PptFieldType;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptPage;
import com.agenttrail.capability.ppt.PptPageType;
import com.agenttrail.capability.ppt.PptPythonRenderer;
import com.agenttrail.capability.ppt.PptSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Arrays;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** 覆盖动态 Schema 到富模板渲染器的最短真实链路。 */
class RenderStrategyRichTemplateTest {

    @Test
    void rendersAllRichLayoutsThroughTheExistingJavaRendererInterface(@TempDir Path tempDir) throws Exception {
        byte[] replacementImage = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imageServer.createContext("/replacement.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, replacementImage.length);
            try (var body = exchange.getResponseBody()) {
                body.write(replacementImage);
            }
        });
        imageServer.start();
        try {
            String imageUrl = "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/replacement.png";
            List<PptPage> pages = List.of(
                page("cover", PptPageType.COVER, Map.of(
                        "title", PptField.text("智能体时代"),
                        "description", PptField.text("从工具调用走向自主协作"),
                        "author", PptField.text("AgentTrail"))),
                page("catalog", PptPageType.CATALOG, Map.of(
                        "catalog1", PptField.text("趋势"),
                        "catalog2", PptField.text("架构"),
                        "catalog3", PptField.text("实践"))),
                page("compare", PptPageType.COMPARE, Map.of(
                        "title", PptField.text("模式对比"),
                        "content1", PptField.text("传统流程：固定编排"),
                        "content2", PptField.text("智能体：动态决策"))),
                page("content", PptPageType.CONTENT, Map.of(
                        "title", PptField.text("核心路径"),
                        "subTitle", PptField.text("闭环"),
                        "content", PptField.text("需求、规划、生成、校验形成稳定闭环"),
                        "image", new PptField(PptFieldType.IMAGE, null, imageUrl, "测试替换图片"))),
                page("end", PptPageType.END, Map.of("title", PptField.text("谢谢"))));
            PptSchema schema = new PptSchema("兼容标题", "兼容副标题", List.of(), null,
                    pages, "default", "1");

            String template = new ClassPathResource("ppt-templates/rich-template.pptx").getFile().getAbsolutePath();
            String script = new ClassPathResource("ppt-scripts/render_ppt_rich.py").getFile().getAbsolutePath();
            RenderStrategy strategy = new RenderStrategy(new PptPythonRenderer("python", script, 60),
                    tempDir.toString());

            Path output = Path.of(strategy.execute(PptGenerationContext.initial("rich-render", "unused")
                    .withTemplatePath(template).withSchema(schema)).outputPath());

            assertThat(output).exists();
            assertThat(Files.size(output)).isGreaterThan(1_000_000L);
            assertThat(countSlides(output)).isEqualTo(5);
            assertThat(readSlideXml(output)).contains("智能体时代", "模式对比", "核心路径", "谢谢");
            assertThat(containsMedia(output, replacementImage)).isTrue();
        } finally {
            imageServer.stop(0);
        }
    }

    private static PptPage page(String id, PptPageType type, Map<String, PptField> fields) {
        return new PptPage(id, type, type.name(), new LinkedHashMap<>(fields), "");
    }

    private static long countSlides(Path output) throws Exception {
        try (ZipFile zip = new ZipFile(output.toFile())) {
            return zip.stream().filter(entry -> entry.getName().matches("ppt/slides/slide\\d+\\.xml")).count();
        }
    }

    private static String readSlideXml(Path output) throws Exception {
        StringBuilder xml = new StringBuilder();
        try (ZipFile zip = new ZipFile(output.toFile())) {
            for (var entry : zip.stream()
                    .filter(candidate -> candidate.getName().matches("ppt/slides/slide\\d+\\.xml")).toList()) {
                xml.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return xml.toString();
    }

    private static boolean containsMedia(Path output, byte[] expected) throws Exception {
        try (ZipFile zip = new ZipFile(output.toFile())) {
            for (var entry : zip.stream().filter(candidate -> candidate.getName().startsWith("ppt/media/"))
                    .toList()) {
                if (Arrays.equals(zip.getInputStream(entry).readAllBytes(), expected)) return true;
            }
        }
        return false;
    }
}
