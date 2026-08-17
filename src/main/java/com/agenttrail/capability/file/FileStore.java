package com.agenttrail.capability.file;

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

    /** 按会话查找全部文件（含 {@code parsedText}/{@code rawBytes} 正文），按上传顺序返回。 */
    List<UploadedFile> findByConversationId(String conversationId);

    /**
     * 系统提示词里"会话文件"区块要列的那些文件（issue #110）：**已绑定到某一轮的 + 本轮显式带上来的**。
     *
     * <p>为什么单独一个方法而不是在 {@link #findByConversationId} 结果上过滤：那个查询会把每个
     * 文件的 {@code parsed_text}（LONGTEXT）和 {@code raw_bytes}（LONGBLOB）全都取出来，而这个
     * 区块只用到 fileName/id/kind/turnId 四个字段。更要命的是本 issue 之后"传了但没发送"的文件会
     * **永久**停在 {@code turn_id IS NULL}（旧 sweep 的自愈没有了），于是一个曾经被拖进来又没发的
     * 20MB 文件，会在此后每一轮都被完整读出来再丢掉。谓词和列裁剪都下推到 SQL。
     *
     * <p><b>返回的对象是元数据投影</b>：{@code parsedText} 和 {@code rawBytes} 一律为 null，
     * 不代表库里没有。要正文请走 {@link #findById}（{@code FileContentTool} 就是这么做的）。
     */
    List<UploadedFile> findVisibleForPrompt(String conversationId, List<Long> requestedFileIds);

    /**
     * 更新已保存文件的 {@code parsedText}（issue #27）：图片首次被问到才调多模态模型，
     * 结果写回这里做懒缓存，下次直接命中，不重复调用。
     */
    void updateParsedText(long id, String parsedText);

    /**
     * 把**用户这一轮显式带上来的**文件回填到刚结束的这一轮（issue #28 建立、issue #110 收紧）。
     *
     * <p>回填而不是上传时就写死，是因为上传发生在这一轮结束之前，那时候轮次 id 还不存在
     * （{@code agent_session} 那行的 answer/timeline 都要等流结束才有值，而 id 是自增主键），
     * 只能等 {@code onTurnComplete} 返回 id 之后才补。
     *
     * <p><b>issue #110：从"扫一遍这个会话里所有 turnId 为 null 的"改成按 {@code fileIds} 精确绑。</b>
     * 旧的扫法让四件事同时成立——发送前删除只是前端幻觉、两个标签页互相吞文件、上传完没发就走人
     * 下次随便问一句都会把它带上、传 3 个只想附 2 个做不到。
     *
     * <p>{@code conversationId} 仍然要传：实现里把它作为 {@code AND conversation_id = ?} 保留，
     * 让跨会话绑定在 SQL 层就不可能发生，比任何上层校验都可靠。
     *
     * @param fileIds 空列表表示这一轮没带文件，实现应直接返回、不做任何写入
     */
    void linkFilesToTurn(String conversationId, List<Long> fileIds, long turnId);

    /** 按主键删除一个文件；id 不存在时静默无操作（调用方在此之前已经确认过存在与归属）。 */
    void delete(long id);
}
