package com.agenttrail.loop.file;

/**
 * 一次文件上传的处理结果（issue #21）——不带解析出的正文，只带"这个文件怎么被路由"的判定，
 * 供上传接口的响应体使用；正文另由 {@link FileQaService#contentFor} 按需取。
 *
 * @param id               生成的文件主键
 * @param fileName         原始文件名
 * @param sizeBytes        文件大小（字节）
 * @param parsedTextLength 解析出的全量文本长度（字符数）
 * @param routedToRag      是否超过阈值、需要走检索问答（这一票只占位判断结果，不做真实检索）
 */
public record IngestedFile(long id, String fileName, long sizeBytes, int parsedTextLength, boolean routedToRag) {
}
