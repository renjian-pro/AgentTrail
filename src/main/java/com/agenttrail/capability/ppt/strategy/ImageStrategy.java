package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptSchema;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.image.PptImageStore;
import com.agenttrail.capability.ppt.image.TextToImageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IMAGE 状态（issue #31）：调文生图 API 拿到的图片 URL 有时效性（DashScope 官方文档原话是返回后
 * 24 小时内有效），必须在这一步立刻下载转存进 MinIO——持久化到 {@link PptSchema#coverImageUrl()}
 * 的必须是 MinIO 的永久 URL，绝不能是 {@link TextToImageClient#generateImageUrl} 直接返回的那个
 * 临时链接，哪怕只是"先存一下、后面状态再补下载"这种拖延也不行：一旦这个中间状态被 checkpoint
 * 落库，断点恢复时临时链接可能已经过期，SCHEMA 里就会永久卡着一个死链接（这正是 issue #31 描述的
 * 参考实现踩过的坑）。
 *
 * <p><b>配图失败要降级，不能拖垮整条 PPT 生成流水线</b>（issue #31 明确的验收标准）——这是这条
 * 状态机里唯一一个把 {@code execute} 内部异常整个吞掉、正常返回的 {@link PptGenerationStrategy}：
 * 其余状态的约定是"异常直接抛出去，交给 {@code PptGenerationService} 记录 checkpoint 失败、状态
 * 原地不动"，这个状态反过来——因为配图纯粹是锦上添花，不是"没有它 PPT 就不成立"的硬需求，
 * RENDER 状态不读 {@code coverImageUrl}，为 {@code null} 时照常只用文字渲染，不应该因为文生图
 * API 抖动/MinIO 一时不可用就让整条已经跑到这一步的流水线连同前面几步的真实调用一起报废重来。
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
        try {
            String prompt = buildCoverImagePrompt(context);
            String temporaryUrl = imageClient.generateImageUrl(prompt);
            String minioUrl = imageStore.downloadAndStore(temporaryUrl, context.conversationId());
            PptSchema schema = context.schema();
            PptSchema schemaWithImage = new PptSchema(
                    schema.titleText(), schema.subtitleText(), schema.contentSlides(), minioUrl);
            return context.withSchema(schemaWithImage);
        } catch (Exception imageFailed) {
            log.warn("PPT 会话 {} 配图生成/转存失败，降级为无封面图继续后续渲染: {}",
                    context.conversationId(), imageFailed.getMessage(), imageFailed);
            return context;
        }
    }

    private static String buildCoverImagePrompt(PptGenerationContext context) {
        var requirement = context.requirement();
        return ("为一份 PPT 生成一张适合作为封面的插画风格配图，主题是「%s」，面向%s，"
                + "画面里不要出现任何文字。").formatted(requirement.topic(), requirement.audience());
    }
}
