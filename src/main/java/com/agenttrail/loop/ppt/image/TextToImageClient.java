package com.agenttrail.loop.ppt.image;

/**
 * 文生图 API 的最小抽象（issue #31）——只有"给一句话 prompt，拿到一个第三方临时图片 URL"这一个
 * 方法，具体走 DashScope（{@link DashScopeImageClient}）还是别的供应商，对上层
 * {@code com.agenttrail.loop.ppt.strategy.ImageStrategy} 透明。
 *
 * <p><b>返回值是有时效性的临时链接，不是可以直接持久化的最终地址</b>——调用方必须立刻下载转存到
 * 自建对象存储，这是这个接口存在的全部意义（也是 issue #31 本身要解决的问题）。失败时抛
 * {@link PptImageException}，不返回 null 静默失败。
 */
public interface TextToImageClient {

    String generateImageUrl(String prompt);
}
