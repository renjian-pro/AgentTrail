package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptField;
import com.agenttrail.capability.ppt.PptFieldType;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptPage;
import com.agenttrail.capability.ppt.PptPageType;
import com.agenttrail.capability.ppt.PptPythonRenderer;
import com.agenttrail.capability.ppt.PptRenderException;
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
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖动态 Schema 到富模板渲染器的最短真实链路。 */
class RenderStrategyRichTemplateTest {

    @Test
    void keepsRichContentInsideItsTemplateFrameAndDropsIncompleteBullet(@TempDir Path tempDir)
            throws Exception {
        String firstBullet = "• 培养跨学科团队：融合业务、技术、数据与治理能力。";
        String secondBullet = "• 推动员工技能升级，从执行者转向监督与优化者。";
        String thirdBullet = "• 建立内部沟通机制，管理变革预期与阻力。";
        PptSchema schema = new PptSchema("标题", "副标题", List.of(), null,
                List.of(page("content", PptPageType.CONTENT, Map.of(
                        "title", PptField.text("组织与人才准备"),
                        "subTitle", PptField.text("组织保障"),
                        "content", PptField.text(String.join("\n", firstBullet, secondBullet, thirdBullet))))),
                "default", "1");
        String template = new ClassPathResource("ppt-templates/rich-template.pptx").getFile().getAbsolutePath();
        String script = new ClassPathResource("ppt-scripts/render_ppt_rich.py").getFile().getAbsolutePath();
        RenderStrategy strategy = new RenderStrategy(new PptPythonRenderer("python", script, 60),
                tempDir.toString());

        Path output = Path.of(strategy.execute(PptGenerationContext.initial("rich-text-fit", "unused")
                .withTemplatePath(template).withSchema(schema)).outputPath());
        ContentShapeSnapshot content = readContentShape(output);

        assertThat(content.text()).isEqualTo(firstBullet + "\n" + secondBullet);
        assertThat(content.normalAutoFit()).as("正文必须收缩在模板文本框内，不能向下扩张出边框").isTrue();
    }

    @Test
    void failsInsteadOfReportingSuccessWhenARequestedImageCannotBeDownloaded(@TempDir Path tempDir)
            throws Exception {
        PptSchema schema = new PptSchema("标题", "副标题", List.of(), null,
                List.of(page("content", PptPageType.CONTENT, Map.of(
                        "title", PptField.text("核心路径"),
                        "image", new PptField(PptFieldType.IMAGE, null,
                                "http://127.0.0.1:1/unreachable.png", "不可达测试图片")))),
                "default", "1");
        String template = new ClassPathResource("ppt-templates/rich-template.pptx").getFile().getAbsolutePath();
        String script = new ClassPathResource("ppt-scripts/render_ppt_rich.py").getFile().getAbsolutePath();
        RenderStrategy strategy = new RenderStrategy(new PptPythonRenderer("python", script, 60),
                tempDir.toString());

        assertThatThrownBy(() -> strategy.execute(PptGenerationContext.initial("rich-render-failure", "unused")
                .withTemplatePath(template).withSchema(schema)))
                .isInstanceOf(PptRenderException.class)
                .hasMessageContaining("图片下载失败");
    }

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
            String storedObjectKey = "ppt/rich-render/content-image.png";
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
                        "image", new PptField(PptFieldType.IMAGE, null, storedObjectKey, "测试替换图片"))),
                page("end", PptPageType.END, Map.of("title", PptField.text("谢谢"))));
            PptSchema schema = new PptSchema("兼容标题", "兼容副标题", List.of(), null,
                    pages, "default", "1");

            String template = new ClassPathResource("ppt-templates/rich-template.pptx").getFile().getAbsolutePath();
            String script = new ClassPathResource("ppt-scripts/render_ppt_rich.py").getFile().getAbsolutePath();
            RenderStrategy strategy = new RenderStrategy(new PptPythonRenderer("python", script, 60),
                    tempDir.toString(), reference -> storedObjectKey.equals(reference)
                            ? "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/replacement.png"
                            : reference);

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

    private static ContentShapeSnapshot readContentShape(Path output) throws Exception {
        try (ZipFile zip = new ZipFile(output.toFile())) {
            var entry = zip.stream()
                    .filter(candidate -> candidate.getName().matches("ppt/slides/slide\\d+\\.xml"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("rendered PPT does not contain a slide XML"));
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(zip.getInputStream(entry));
            var properties = document.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/presentationml/2006/main", "cNvPr");
            for (int index = 0; index < properties.getLength(); index++) {
                Element property = (Element) properties.item(index);
                if (!"content".equals(property.getAttribute("name"))) continue;
                Node shape = property;
                while (shape != null && !"sp".equals(shape.getLocalName())) shape = shape.getParentNode();
                if (!(shape instanceof Element shapeElement)) break;
                var textNodes = shapeElement.getElementsByTagNameNS(
                        "http://schemas.openxmlformats.org/drawingml/2006/main", "t");
                StringBuilder text = new StringBuilder();
                for (int textIndex = 0; textIndex < textNodes.getLength(); textIndex++) {
                    if (textIndex > 0) text.append('\n');
                    text.append(textNodes.item(textIndex).getTextContent());
                }
                boolean normalAutoFit = shapeElement.getElementsByTagNameNS(
                        "http://schemas.openxmlformats.org/drawingml/2006/main", "normAutofit").getLength() > 0;
                return new ContentShapeSnapshot(text.toString(), normalAutoFit);
            }
        }
        throw new AssertionError("rendered PPT does not contain shape named content");
    }

    private record ContentShapeSnapshot(String text, boolean normalAutoFit) { }
}
