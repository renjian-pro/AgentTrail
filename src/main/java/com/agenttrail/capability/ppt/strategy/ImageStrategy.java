package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptAssetKey;
import com.agenttrail.capability.ppt.PptCancellationException;
import com.agenttrail.capability.ppt.PptCancellationToken;
import com.agenttrail.capability.ppt.PptFieldType;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptSchema;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptVisualPlan;
import com.agenttrail.capability.ppt.PptWarning;
import com.agenttrail.capability.ppt.image.PptImageStore;
import com.agenttrail.capability.ppt.image.TextToImageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * IMAGE 状态：把第三方临时图片链接立即转存为稳定 artifact，并以逐页素材任务记录 attempt、
 * provenance 和降级结果。配图是可选增强，失败时仍返回上下文，但 warning/asset 状态会随 checkpoint
 * 写入，重试不会丢失“为什么没有图”的信息。
 */
public class ImageStrategy implements PptGenerationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ImageStrategy.class);

    private final TextToImageClient imageClient;
    private final PptImageStore imageStore;

    public ImageStrategy(TextToImageClient imageClient, PptImageStore imageStore) {
        this.imageClient = imageClient;
        this.imageStore = imageStore;
    }

    @Override
    public PptState handledState() {
        return PptState.IMAGE;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        return execute(context, PptCancellationToken.never());
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context, PptCancellationToken cancellationToken) {
        return execute(context, cancellationToken, ProgressReporter.noop());
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context, PptCancellationToken cancellationToken,
            ProgressReporter progressReporter) {
        cancellationToken.throwIfCancellationRequested();
        if (!context.schema().pages().isEmpty()) {
            return generateDynamicImages(context, cancellationToken, progressReporter);
        }
        return generateLegacyCover(context, cancellationToken, progressReporter);
    }

    /** 动态 Schema 自身就是任务清单：逐个生成空 artifactId 的 IMAGE 字段并原位回写。 */
    private PptGenerationContext generateDynamicImages(PptGenerationContext context,
            PptCancellationToken cancellationToken, ProgressReporter progressReporter) {
        PptSchema schema = context.schema();
        PptGenerationContext working = context;
        int total = (int) context.schema().pages().stream()
                .flatMap(page -> page.fields().values().stream())
                .filter(field -> field.type() == PptFieldType.IMAGE)
                .filter(field -> field.artifactId() == null || field.artifactId().isBlank())
                .filter(field -> {
                    String prompt = field.value() == null ? field.text() : String.valueOf(field.value());
                    return prompt != null && !prompt.isBlank();
                })
                .count();
        progressReporter.report(PptState.IMAGE, "开始生成图片素材（0/" + total + "）", null);
        int current = 0;
        for (var page : context.schema().pages()) {
            for (var entry : page.fields().entrySet()) {
                var field = entry.getValue();
                if (field.type() != PptFieldType.IMAGE
                        || (field.artifactId() != null && !field.artifactId().isBlank())) {
                    continue;
                }
                String prompt = field.value() == null ? field.text() : String.valueOf(field.value());
                if (prompt == null || prompt.isBlank()) continue;
                current++;
                long imageStartedAt = System.nanoTime();
                try {
                    cancellationToken.throwIfCancellationRequested();
                    long generationStartedAt = System.nanoTime();
                    String temporaryUrl = imageClient.generateImageUrl(prompt, cancellationToken);
                    long generationMillis = elapsedMillis(generationStartedAt);
                    String stableKey = PptAssetKey.derive(context.conversationId(), page.pageId(),
                            entry.getKey(), prompt);
                    long storeStartedAt = System.nanoTime();
                    String minioUrl = imageStore.downloadAndStore(temporaryUrl, context.conversationId(),
                            stableKey, cancellationToken);
                    long storeMillis = elapsedMillis(storeStartedAt);
                    schema = schema.withImageArtifact(page.pageId(), entry.getKey(), minioUrl);
                    working = working.withSchema(schema);
                    progressReporter.report(PptState.IMAGE,
                            "图片生成完成（" + current + "/" + total + "）", null);
                    log.info("PPT image completed conversationId={} pageId={} fieldName={} current={} total={} generationMs={} storeMs={} durationMs={}",
                            context.conversationId(), page.pageId(), entry.getKey(), current, total,
                            generationMillis, storeMillis, elapsedMillis(imageStartedAt));
                } catch (PptCancellationException cancelled) {
                    throw cancelled;
                } catch (Exception imageFailed) {
                    log.warn("PPT image degraded conversationId={} pageId={} fieldName={} current={} total={} durationMs={}",
                            context.conversationId(), page.pageId(), entry.getKey(), current, total,
                            elapsedMillis(imageStartedAt), imageFailed);
                    progressReporter.report(PptState.IMAGE,
                            "图片生成失败，已保留模板图片（" + current + "/" + total + "）",
                            "PPT_IMAGE_DEGRADED");
                    working = working.withWarning(new PptWarning("PPT_IMAGE_DEGRADED", PptState.IMAGE,
                            "页面配图生成失败，已保留模板图片", List.of(page.pageId())));
                }
            }
        }
        return working;
    }

    /** 兼容没有动态 pages 的历史 Schema，仍生成原有封面图。 */
    private PptGenerationContext generateLegacyCover(PptGenerationContext context,
            PptCancellationToken cancellationToken, ProgressReporter progressReporter) {
        String prompt = buildCoverImagePrompt(context);
        progressReporter.report(PptState.IMAGE, "开始生成封面图片（0/1）", null);
        long startedAt = System.nanoTime();
        try {
            String temporaryUrl = imageClient.generateImageUrl(prompt, cancellationToken);
            String stableKey = PptAssetKey.derive(context.conversationId(), "cover", "coverImage", prompt);
            String minioUrl = imageStore.downloadAndStore(temporaryUrl, context.conversationId(), stableKey,
                    cancellationToken);
            PptSchema schemaWithImage = context.schema().withCoverImageUrl(minioUrl);
            progressReporter.report(PptState.IMAGE, "封面图片生成完成（1/1）", null);
            log.info("PPT cover image completed conversationId={} current=1 total=1 durationMs={}",
                    context.conversationId(), elapsedMillis(startedAt));
            return context.withSchema(schemaWithImage);
        } catch (PptCancellationException cancelled) {
            throw cancelled;
        } catch (Exception imageFailed) {
            log.warn("PPT cover image degraded conversationId={} current=1 total=1 durationMs={}",
                    context.conversationId(), elapsedMillis(startedAt), imageFailed);
            progressReporter.report(PptState.IMAGE, "封面图片生成失败，已降级继续（1/1）",
                    "PPT_IMAGE_DEGRADED");
            return context.withWarning(new PptWarning("PPT_IMAGE_DEGRADED", PptState.IMAGE,
                            "封面图生成失败，已降级为无图版本继续生成", List.of()));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String buildCoverImagePrompt(PptGenerationContext context) {
        var requirement = context.requirement();
        PptVisualPlan visual = context.visualPlan() == null
                ? PptVisualPlan.defaultFor(requirement) : context.visualPlan();
        return PptPrompts.IMAGE.formatted(
                requirement.topic(),
                requirement.audience(),
                visual.imageStyle(),
                visual.pageSize(),
                visual.styleKeywords(),
                visual.primaryColor(),
                visual.backgroundColor());
    }
}
