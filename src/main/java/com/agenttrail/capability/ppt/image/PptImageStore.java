package com.agenttrail.capability.ppt.image;

import com.agenttrail.capability.ppt.PptCancellationToken;

/**
 * 图片下载 + 转存 MinIO 的最小抽象（issue #31）——"拿到第三方临时 URL，返回自建 MinIO 上的永久
 * URL"这一个方法，具体走真实 MinIO SDK（{@link MinioPptImageStore}）还是别的对象存储，对上层
 * {@code com.agenttrail.capability.ppt.strategy.ImageStrategy} 透明。
 *
 * <p>失败时抛 {@link PptImageException}，是否降级（配图失败不能拖垮整条 PPT 生成流水线）是调用方
 * 的职责——这一层只负责"要么真的存成功返回 MinIO URL，要么老实抛异常"，不会返回 null 静默失败，
 * 那样会让调用方误以为"没有图"是正常状态，而不是"转存出错了"。
 */
public interface PptImageStore {

    /**
     * @param temporaryImageUrl 文生图 API 返回的有时效性临时链接
     * @param objectKeyPrefix   MinIO 对象 key 的前缀（调用方一般传会话/任务标识，方便运维排查这张图
     *                          是哪次 PPT 生成产出的），真正的 key 在这个前缀基础上追加随机后缀，
     *                          避免同一前缀并发写入互相覆盖
     * @return 自建 MinIO 上的永久可访问 URL
     */
    String downloadAndStore(String temporaryImageUrl, String objectKeyPrefix);

    /** 默认适配旧对象存储；实现可覆盖并中断长时间的 HTTP 下载。 */
    default String downloadAndStore(String temporaryImageUrl, String objectKeyPrefix,
            PptCancellationToken cancellationToken) {
        cancellationToken.throwIfCancellationRequested();
        String result = downloadAndStore(temporaryImageUrl, objectKeyPrefix);
        cancellationToken.throwIfCancellationRequested();
        return result;
    }
}
