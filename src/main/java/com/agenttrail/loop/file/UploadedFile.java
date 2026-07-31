package com.agenttrail.loop.file;

/**
 * 一个上传文件的元数据 + Tika 解析出的全量文本（issue #21）。
 *
 * <p>{@code id}/{@code turnId} 在保存前都是 {@code null}：{@code id} 由数据库生成，
 * {@code turnId} 要等这一轮问答结束才知道（对齐 {@code agent_session} 的双 key 设计，
 * 见 {@code db/schema.sql} 里 {@code agent_file} 表的注释）——这一票只建立持久化模型，
 * 回填 {@code turnId} 是后续"多轮文件生命周期"那张票（issue #28）的范围。
 *
 * <p>{@code parsedText} 永远存全量文本，不管是否超过阈值：超阈值的大文件一样需要这份全文
 * 供后续 RAG 分片（issue #26）使用，阈值只影响{@link FileQaService#contentFor}把多少内容
 * 直接喂给模型，不影响这里存什么。
 *
 * @param id             主键，保存前为 null
 * @param conversationId 会话标识，跨轮可见
 * @param turnId         归属的单轮问答 id（{@code agent_session.id}），保存时为 null
 * @param fileName       原始文件名
 * @param contentType    上传时的 MIME 类型，可能为 null
 * @param sizeBytes      文件大小（字节）
 * @param parsedText     Tika 解析出的全量文本
 * @param createdAtMillis 上传时刻
 */
public record UploadedFile(
        Long id,
        String conversationId,
        Long turnId,
        String fileName,
        String contentType,
        long sizeBytes,
        String parsedText,
        long createdAtMillis) {
}
