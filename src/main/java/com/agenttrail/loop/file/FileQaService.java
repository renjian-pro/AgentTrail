package com.agenttrail.loop.file;

import java.io.InputStream;
import java.time.Clock;
import java.util.NoSuchElementException;

/**
 * 文件问答的核心编排（issue #21）：上传时解析+落库，取内容时按字符数阈值分流——
 * 阈值以内把 Tika 解析出的全量文本直接作为"工具结果"喂给模型；超过阈值先占位返回
 * "文件过大，走检索问答"，真正的检索管线是下一张票（issue #26）的范围。
 *
 * <p>{@code parsedText} 无论是否超过阈值都整份存进 {@link FileStore}——阈值只影响
 * {@link #contentFor} 返回多少内容，不影响持久化的内容，这样后续 RAG 分片不需要重新解析。
 */
public class FileQaService {

    /** 超过阈值时返回的占位提示，前半句带上实际字符数和阈值方便排查。 */
    private static final String RAG_PLACEHOLDER_TEMPLATE =
            "文件过大（解析后 %d 字符，超过阈值 %d），请使用检索问答";

    private final FileStore fileStore;
    private final FileTextParser parser;
    private final int ragThresholdChars;
    private final Clock clock;

    public FileQaService(FileStore fileStore, FileTextParser parser, int ragThresholdChars) {
        this(fileStore, parser, ragThresholdChars, Clock.systemUTC());
    }

    FileQaService(FileStore fileStore, FileTextParser parser, int ragThresholdChars, Clock clock) {
        this.fileStore = fileStore;
        this.parser = parser;
        this.ragThresholdChars = ragThresholdChars;
        this.clock = clock;
    }

    /**
     * 解析并持久化一个新上传的文件。
     *
     * @param conversationId 所属会话；跨轮可见，{@code turnId} 要等这一轮结束才回填（issue #28 的范围）
     * @param fileName       原始文件名
     * @param contentType    上传时的 MIME 类型，可能为 null
     * @param content        文件原始字节流，本方法负责读取但不负责关闭——关闭由调用方（HTTP 层）处理
     */
    public IngestedFile ingest(String conversationId, String fileName, String contentType,
            InputStream content, long sizeBytes) {
        String parsedText = parser.parse(content, fileName);
        UploadedFile toSave = new UploadedFile(
                null, conversationId, null, fileName, contentType, sizeBytes, parsedText, clock.millis());
        long id = fileStore.save(toSave);
        boolean routedToRag = parsedText.length() > ragThresholdChars;
        return new IngestedFile(id, fileName, sizeBytes, parsedText.length(), routedToRag);
    }

    /**
     * 按阈值返回"喂给模型的内容"：阈值以内是解析出的全量文本，超过阈值是占位提示。
     *
     * @throws NoSuchElementException fileId 不存在
     */
    public String contentFor(long fileId) {
        UploadedFile file = fileStore.findById(fileId)
                .orElseThrow(() -> new NoSuchElementException("未知的文件标识: " + fileId));
        String parsedText = file.parsedText();
        if (parsedText.length() <= ragThresholdChars) {
            return parsedText;
        }
        return RAG_PLACEHOLDER_TEMPLATE.formatted(parsedText.length(), ragThresholdChars);
    }
}
