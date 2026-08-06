package com.agenttrail.capability.file;

/**
 * 一个上传文件的元数据 + 正文表示（issue #21/#26/#27）。
 *
 * <p>{@code id}/{@code turnId} 在保存前都是 {@code null}：{@code id} 由数据库生成，
 * {@code turnId} 要等这一轮问答结束才知道（对齐 {@code agent_session} 的双 key 设计，
 * 见 {@code db/schema.sql} 里 {@code agent_file} 表的注释）——这一票只建立持久化模型，
 * 回填 {@code turnId} 是后续"多轮文件生命周期"那张票（issue #28）的范围。
 *
 * <p>{@code parsedText} 的含义随 {@code kind} 变化：{@link FileKind#TEXT} 时是 Tika 解析出的
 * 全量文本（无论是否超过 RAG 阈值都存全量）；{@link FileKind#IMAGE} 时是多模态模型描述的懒缓存——
 * 上传时为 {@code null}，首次被问到才调用模型并写回（{@link FileStore#updateParsedText}），
 * 之后直接命中缓存。两种含义共用一列是刻意的（参考实现同样如此）：本质上都是
 * "这个文件当前已知的文本表示"，没必要为图片另建一列。
 *
 * <p>{@code rawBytes} 只有 {@link FileKind#IMAGE} 会填（供懒加载时读出来调多模态模型），
 * {@link FileKind#TEXT} 始终为 {@code null}——文本已经在上传时解析完，不需要留着原始字节。
 *
 * <p>注意：{@code byte[]} 字段导致这个 record 的 {@code equals()}/{@code hashCode()} 对
 * {@code rawBytes} 是引用比较，不是内容比较——测试断言整份 record 相等时要留意这一点，
 * 必要时改成按字段比较。
 *
 * @param id             主键，保存前为 null
 * @param conversationId 会话标识，跨轮可见
 * @param turnId         归属的单轮问答 id（{@code agent_session.id}），保存时为 null
 * @param fileName       原始文件名
 * @param contentType    上传时的 MIME 类型，可能为 null
 * @param sizeBytes      文件大小（字节）
 * @param kind           文件路由到哪条问答链路
 * @param parsedText     文本文件是 Tika 解析全文；图片是多模态描述的懒缓存，未识别前为 null
 * @param rawBytes       图片原始字节，供懒加载识别时读取；文本文件始终为 null
 * @param createdAtMillis 上传时刻
 */
public record UploadedFile(
        Long id,
        String userId,
        String conversationId,
        Long turnId,
        String fileName,
        String contentType,
        long sizeBytes,
        FileKind kind,
        String parsedText,
        byte[] rawBytes,
        long createdAtMillis) {

    /** 兼容早期内存测试构造器；生产请求必须传入真实 userId。 */
    public UploadedFile(Long id, String conversationId, Long turnId, String fileName, String contentType,
            long sizeBytes, FileKind kind, String parsedText, byte[] rawBytes, long createdAtMillis) {
        this(id, null, conversationId, turnId, fileName, contentType, sizeBytes, kind, parsedText, rawBytes,
                createdAtMillis);
    }
}
