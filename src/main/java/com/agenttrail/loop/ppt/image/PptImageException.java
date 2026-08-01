package com.agenttrail.loop.ppt.image;

/**
 * 配图生成/下载/转存 MinIO 全流程共用的统一异常（issue #31）——{@code ImageStrategy} 按这一个
 * 类型统一捕获做降级，不需要分别处理"调用文生图 API 失败"和"下载/上传 MinIO 失败"两种异常类型。
 */
public class PptImageException extends RuntimeException {

    public PptImageException(String message) {
        super(message);
    }

    public PptImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
