package com.agenttrail.loop.file;

import com.agenttrail.loop.rag.RagRetrievalService;
import com.agenttrail.loop.rag.VectorizationException;
import com.agenttrail.loop.rag.FileVectorizationService;

import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 文件问答的核心编排：上传时解析+落库（issue #21），超过阈值的大文件额外分块入库到向量库
 * （issue #26）；取内容时按阈值分流——阈值以内直接返回 Tika 解析出的全量文本，
 * 超过阈值走 RAG 检索管线，用检索到的片段而不是整份原文回答。
 *
 * <p>{@code parsedText} 无论是否超过阈值都整份存进 {@link FileStore}——阈值只影响
 * {@link #contentFor} 返回什么，不影响持久化的内容。
 *
 * <p>大文件的向量化失败（{@link VectorizationException}）会从 {@link #ingest} 直接抛出去：
 * 此时 {@link FileStore} 里已经有这份文件的元数据行了（分块打标签要用到生成的 fileId，
 * 必须先落库拿到 id），向量化失败留下一条"检索不到任何内容"的孤儿记录，
 * 但这比参考实现"打个 warn 日志、假装传成功了"更诚实——调用方能立刻看到失败，
 * 而不是要等到用户问起这份文件才发现检索一直是空的。
 */
public class FileQaService {

    /** 大文件还没问过问题时的占位提示。 */
    private static final String NO_QUESTION_YET_TEMPLATE =
            "文件过大（解析后 %d 字符，超过阈值 %d），请携带具体问题走检索问答";
    private static final String NO_RESULTS_TEMPLATE = "未检索到与问题相关的内容";

    private final FileStore fileStore;
    private final FileTextParser parser;
    private final FileVectorizationService vectorizationService;
    private final RagRetrievalService retrievalService;
    private final int ragThresholdChars;
    private final Clock clock;

    public FileQaService(FileStore fileStore, FileTextParser parser, FileVectorizationService vectorizationService,
            RagRetrievalService retrievalService, int ragThresholdChars) {
        this(fileStore, parser, vectorizationService, retrievalService, ragThresholdChars, Clock.systemUTC());
    }

    FileQaService(FileStore fileStore, FileTextParser parser, FileVectorizationService vectorizationService,
            RagRetrievalService retrievalService, int ragThresholdChars, Clock clock) {
        this.fileStore = fileStore;
        this.parser = parser;
        this.vectorizationService = vectorizationService;
        this.retrievalService = retrievalService;
        this.ragThresholdChars = ragThresholdChars;
        this.clock = clock;
    }

    /**
     * 解析并持久化一个新上传的文件；超过阈值时额外分块入库到向量库。
     *
     * @param conversationId 所属会话；跨轮可见，{@code turnId} 要等这一轮结束才回填（issue #28 的范围）
     * @param fileName       原始文件名
     * @param contentType    上传时的 MIME 类型，可能为 null
     * @param content        文件原始字节流，本方法负责读取但不负责关闭——关闭由调用方（HTTP 层）处理
     * @throws VectorizationException 大文件向量化失败
     */
    public IngestedFile ingest(String conversationId, String fileName, String contentType,
            InputStream content, long sizeBytes) {
        String parsedText = parser.parse(content, fileName);
        boolean routedToRag = parsedText.length() > ragThresholdChars;

        long id = fileStore.save(new UploadedFile(
                null, conversationId, null, fileName, contentType, sizeBytes, parsedText, clock.millis()));

        if (routedToRag) {
            vectorizationService.vectorize(id, parsedText);
        }

        return new IngestedFile(id, fileName, sizeBytes, parsedText.length(), routedToRag);
    }

    /**
     * 按阈值返回"喂给模型的内容"：阈值以内是解析出的全量文本；超过阈值时，没给问题就提示
     * 需要问题，给了问题就跑 RAG 检索管线，返回检索到的片段拼接。
     *
     * @param question 大文件走检索问答时用的问题；小文件忽略这个参数
     * @throws NoSuchElementException fileId 不存在
     */
    public String contentFor(long fileId, String question) {
        UploadedFile file = fileStore.findById(fileId)
                .orElseThrow(() -> new NoSuchElementException("未知的文件标识: " + fileId));
        String parsedText = file.parsedText();
        if (parsedText.length() <= ragThresholdChars) {
            return parsedText;
        }
        if (question == null || question.isBlank()) {
            return NO_QUESTION_YET_TEMPLATE.formatted(parsedText.length(), ragThresholdChars);
        }
        List<String> retrieved = retrievalService.retrieve(fileId, question);
        if (retrieved.isEmpty()) {
            return NO_RESULTS_TEMPLATE;
        }
        return String.join("\n\n---\n\n", retrieved);
    }
}
