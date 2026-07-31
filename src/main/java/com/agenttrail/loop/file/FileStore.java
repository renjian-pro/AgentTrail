package com.agenttrail.loop.file;

import java.util.List;
import java.util.Optional;

/**
 * 上传文件的存取接口（issue #21）。参照 {@link com.agenttrail.loop.pause.PauseStateStore}/
 * {@link com.agenttrail.loop.memory.MemoryStore} 的既有模式：接口不假设存储介质。
 *
 * <p>和 {@code MemoryStore}/{@code TraceStore} 不同，这里需要按主键单独查找（工具按 fileId
 * 取内容），所以保存要返回生成的主键，而不是像那两个 Store 一样返回 void。
 */
public interface FileStore {

    /** 保存一个新上传的文件，返回生成的主键；入参的 {@code id}/{@code turnId} 应为 null。 */
    long save(UploadedFile file);

    /** 按主键查找单个文件。 */
    Optional<UploadedFile> findById(long id);

    /** 按会话查找全部文件，按上传顺序返回。 */
    List<UploadedFile> findByConversationId(String conversationId);

    /**
     * 更新已保存文件的 {@code parsedText}（issue #27）：图片首次被问到才调多模态模型，
     * 结果写回这里做懒缓存，下次直接命中，不重复调用。
     */
    void updateParsedText(long id, String parsedText);
}
