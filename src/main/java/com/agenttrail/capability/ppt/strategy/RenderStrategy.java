package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptContentSlidePayload;
import com.agenttrail.capability.ppt.PptDynamicPagePayload;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptCancellationToken;
import com.agenttrail.capability.ppt.ProcessBuilderRenderPort;
import com.agenttrail.capability.ppt.PptPythonRenderer;
import com.agenttrail.capability.ppt.RenderPort;
import com.agenttrail.capability.ppt.PptRenderPayload;
import com.agenttrail.capability.ppt.PptSchema;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptTemplateSpec;
import com.agenttrail.capability.ppt.PptTextFill;
import com.agenttrail.capability.ppt.PptField;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * RENDER 状态（issue #24）：把 {@link PptSchema}（面向模型的语义化结构）翻译成
 * {@link PptRenderPayload}（面向 {@code render_ppt.py} 的、按模板 shape name 组织的机械化结构），
 * 写成一个 JSON 临时文件，交给 {@link PptPythonRenderer} 起 Python 子进程真正渲染。
 *
 * <p>这个状态的"副作用真正完成"就是"Python 子进程成功退出且产物文件确实存在"——
 * {@link PptPythonRenderer#render} 内部用 {@code waitFor(timeout, unit)} 同步阻塞到这一刻，
 * 任何失败路径（启动失败/超时/非零退出码/产物缺失）都会抛异常，这个方法就不会返回，
 * {@code PptGenerationService} 也就不会把状态推进到 {@code SUCCESS}——这正是 issue #24 要求
 * 避免的"配图/渲染还没真正完成就把 checkpoint 写成下一个状态"这个坑在这里的具体落地。
 */
public class RenderStrategy implements PptGenerationStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RenderPort renderPort;
    private final Path outputDir;
    private final Executor renderExecutor;

    public RenderStrategy(PptPythonRenderer renderer, String outputDir) {
        this(new ProcessBuilderRenderPort(renderer), outputDir, null);
    }

    public RenderStrategy(RenderPort renderPort, String outputDir, Executor renderExecutor) {
        this.renderPort = renderPort;
        this.outputDir = Path.of(outputDir);
        this.renderExecutor = renderExecutor;
    }

    @Override
    public PptState handledState() {
        return PptState.RENDER;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        return execute(context, PptCancellationToken.never());
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context, PptCancellationToken cancellationToken) {
        cancellationToken.throwIfCancellationRequested();
        try {
            Files.createDirectories(outputDir);
        } catch (IOException createDirFailed) {
            throw new PptGenerationException("创建 PPT 输出目录失败: " + outputDir, createDirFailed);
        }

        // 每次执行（包括断点恢复重跑这个状态）都用一个新的文件名，不复用上一次失败留下的半成品——
        // 按状态粒度恢复本来就意味着"这个状态整个重新跑一遍"（踩坑点 #44），文件名不需要幂等
        Path workDir = outputDir.resolve(UUID.randomUUID().toString()).normalize();
        Path schemaFile = workDir.resolve("schema.json");
        Path outputFile = workDir.resolve("presentation.pptx");

        PptRenderPayload payload = toRenderPayload(context.schema());
        try {
            Files.createDirectories(workDir);
            Files.writeString(schemaFile, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
        } catch (IOException writeFailed) {
            throw new PptGenerationException("写入渲染用 schema JSON 文件失败: " + schemaFile, writeFailed);
        }

        try {
            String fixedTemplatePath = context.templateRef() == null
                    ? context.templatePath() : context.templateRef().templatePath();
            Runnable render = () -> renderPort.render(Path.of(fixedTemplatePath).toAbsolutePath().normalize(),
                    schemaFile, outputFile, cancellationToken);
            if (renderExecutor == null) {
                render.run();
            } else {
                try {
                    CompletableFuture.runAsync(render, renderExecutor).join();
                } catch (CompletionException wrapped) {
                    if (wrapped.getCause() instanceof com.agenttrail.capability.ppt.PptCancellationException cancelled) {
                        throw cancelled;
                    }
                    throw wrapped;
                }
            }
        } finally {
            try {
                Files.deleteIfExists(schemaFile);
            } catch (IOException ignored) {
                // Keep the work directory for diagnosis when cleanup cannot complete.
            }
        }

        cancellationToken.throwIfCancellationRequested();
        return context.withOutputPath(outputFile.toAbsolutePath().toString());
    }

    private static PptRenderPayload toRenderPayload(PptSchema schema) {
        if (schema.pages() != null && !schema.pages().isEmpty()) {
            List<PptDynamicPagePayload> pages = schema.pages().stream()
                    .map(page -> new PptDynamicPagePayload(page.pageId(), page.pageType().name(),
                            page.templatePageRef(), page.fields().entrySet().stream()
                                    .map(entry -> toTextFill(entry.getKey(), entry.getValue()))
                                    .toList(), page.speakerNotes()))
                    .toList();
            // 动态页也提供 legacy 填充，旧渲染脚本/旧模板仍能安全降级；新脚本优先消费 pages。
            PptDynamicPagePayload cover = pages.get(0);
            List<PptTextFill> titleFills = cover.fills();
            List<PptContentSlidePayload> content = pages.subList(1, pages.size()).stream()
                    .map(page -> new PptContentSlidePayload(page.fills())).toList();
            return new PptRenderPayload(titleFills, content, schema.coverImageUrl(), pages);
        }
        List<PptTextFill> titleSlideFills = List.of(
                new PptTextFill(PptTemplateSpec.TITLE_SHAPE, schema.titleText(), PptTemplateSpec.TITLE_FONT_LIMIT),
                new PptTextFill(PptTemplateSpec.SUBTITLE_SHAPE, schema.subtitleText(),
                        PptTemplateSpec.SUBTITLE_FONT_LIMIT));
        List<PptContentSlidePayload> contentSlides = schema.contentSlides().stream()
                .map(fill -> new PptContentSlidePayload(List.of(
                        new PptTextFill(PptTemplateSpec.CONTENT_TITLE_SHAPE, fill.slideTitleText(),
                                PptTemplateSpec.CONTENT_TITLE_FONT_LIMIT),
                        new PptTextFill(PptTemplateSpec.CONTENT_BODY_SHAPE, fill.slideBodyText(),
                                PptTemplateSpec.CONTENT_BODY_FONT_LIMIT))))
                .toList();
        // schema.coverImageUrl()（issue #31）此前从没被翻译进渲染载荷——render_ppt.py 之前根本
        // 不认识这个字段，标题页永远只有文字。这一票（issue #33）把它接上，和内容页的装饰图形
        // 共用同一套"渲染载荷带图片信息、render_ppt.py 负责真正贴图"机制，见 PptRenderPayload
        // 类注释。为 null（没配图/断点续传时旧任务没有这个字段）时 render_ppt.py 自己退化成
        // Pillow 装饰图形兜底，这里不需要做任何 null 特判。
        return new PptRenderPayload(titleSlideFills, contentSlides, schema.coverImageUrl(), List.of());
    }

    private static PptTextFill toTextFill(String fieldName, PptField field) {
        String text = field.text() != null ? field.text() : field.value() == null ? "" : String.valueOf(field.value());
        String shapeName = switch (fieldName) {
            case "title", "titleText", "title_text" -> PptTemplateSpec.TITLE_SHAPE;
            case "subtitle", "subtitleText", "subtitle_text" -> PptTemplateSpec.SUBTITLE_SHAPE;
            case "slideTitle", "slideTitleText", "slide_title_text" -> PptTemplateSpec.CONTENT_TITLE_SHAPE;
            case "body", "content", "slideBody", "slideBodyText", "slide_body_text" -> PptTemplateSpec.CONTENT_BODY_SHAPE;
            default -> fieldName;
        };
        int fontLimit = shapeName.equals(PptTemplateSpec.TITLE_SHAPE) ? PptTemplateSpec.TITLE_FONT_LIMIT
                : shapeName.equals(PptTemplateSpec.SUBTITLE_SHAPE) ? PptTemplateSpec.SUBTITLE_FONT_LIMIT
                : shapeName.equals(PptTemplateSpec.CONTENT_TITLE_SHAPE) ? PptTemplateSpec.CONTENT_TITLE_FONT_LIMIT
                : PptTemplateSpec.CONTENT_BODY_FONT_LIMIT;
        return new PptTextFill(shapeName, text, fontLimit);
    }
}
