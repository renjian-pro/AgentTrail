package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptAssetKey;
import com.agenttrail.capability.ppt.PptAssetProvenance;
import com.agenttrail.capability.ppt.PptAssetSource;
import com.agenttrail.capability.ppt.PptAssetTask;
import com.agenttrail.capability.ppt.PptAssetPlanner;
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
import java.util.ArrayList;

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
        cancellationToken.throwIfCancellationRequested();
        String prompt = buildCoverImagePrompt(context);
        String contentDigest = PptAssetKey.digest(prompt);
        PptAssetTask planned = PptAssetTask.planned("cover", "coverImage", PptFieldType.IMAGE,
                prompt, context.visualPlan(), contentDigest);
        // 先把 Schema 中所有图片字段登记为 PLANNED，再执行当前支持的封面图；未来的图片执行器
        // 可以复用同一列表，不会因为新增页面类型而再造一套素材任务协议。
        List<PptAssetTask> mergedPlan = new ArrayList<>(context.assetTasks());
        for (PptAssetTask candidate : PptAssetPlanner.plan(context.schema(), context.visualPlan())) {
            boolean exists = mergedPlan.stream().anyMatch(existing -> existing.pageId().equals(candidate.pageId())
                    && existing.fieldName().equals(candidate.fieldName()));
            if (!exists) mergedPlan.add(candidate);
        }
        PptGenerationContext working = context.withAssetTasks(mergedPlan).replaceAssetTask(planned.running());
        try {
            String temporaryUrl = imageClient.generateImageUrl(prompt, cancellationToken);
            String stableKey = PptAssetKey.derive(context.conversationId(), "cover", "coverImage", prompt);
            String minioUrl = imageStore.downloadAndStore(temporaryUrl, context.conversationId(), stableKey,
                    cancellationToken);
            PptSchema schemaWithImage = context.schema().withCoverImageUrl(minioUrl);
            PptAssetTask completed = planned.running().succeeded(minioUrl,
                    new PptAssetProvenance(PptAssetSource.TEXT_TO_IMAGE, null, null, null, 0, 0, 0));
            return working.withSchema(schemaWithImage).replaceAssetTask(completed);
        } catch (PptCancellationException cancelled) {
            throw cancelled;
        } catch (Exception imageFailed) {
            log.warn("PPT 会话 {} 配图生成/转存失败，降级为无封面图继续后续渲染: {}",
                    context.conversationId(), imageFailed.getMessage(), imageFailed);
            return working.replaceAssetTask(planned.running().failed())
                    .withWarning(new PptWarning("PPT_IMAGE_DEGRADED", PptState.IMAGE,
                            "封面图生成失败，已降级为无图版本继续生成", List.of()));
        }
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
