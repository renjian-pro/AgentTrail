package com.agenttrail.loop.file;

/**
 * 一次文件上传的处理结果（issue #21/#27）——不带解析出的正文，只带"这个文件怎么被路由"的判定，
 * 供上传接口的响应体使用；正文另由 {@link FileQaService#contentFor} 按需取。
 *
 * @param id               生成的文件主键
 * @param fileName         原始文件名
 * @param kind             文件被判定成了哪种类型
 * @param sizeBytes        文件大小（字节）
 * @param parsedTextLength 解析出的全量文本长度（字符数）；图片恒为 0——图片描述是懒加载的，
 *                         上传时还没有
 * @param routedToRag      是否超过阈值、需要走检索问答；图片恒为 false
 */
public record IngestedFile(long id, String fileName, FileKind kind, long sizeBytes, int parsedTextLength,
        boolean routedToRag) {
}
