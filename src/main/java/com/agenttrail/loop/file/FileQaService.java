package com.agenttrail.loop.file;

import com.agenttrail.loop.multimodal.ImageDescriptionService;
import com.agenttrail.loop.rag.RagRetrievalService;
import com.agenttrail.loop.rag.VectorizationException;
import com.agenttrail.loop.rag.FileVectorizationService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 文件问答的核心编排：按文件类型三路分发（issue #27）——
 * <ul>
 *   <li>{@link FileKind#IMAGE}：不解析、不进 RAG，懒加载调多模态模型描述，结果写回缓存
 *   <li>{@link FileKind#TEXT} 阈值以内：Tika 解析全文直接作为内容（issue #21）
 *   <li>{@link FileKind#TEXT} 超过阈值：额外分块入库到向量库，取内容时走 RAG 检索（issue #26）
 * </ul>
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
    private final ImageDescriptionService imageDescriptionService;
    private final int ragThresholdChars;
    private final Clock clock;

    public FileQaService(FileStore fileStore, FileTextParser parser, FileVectorizationService vectorizationService,
            RagRetrievalService retrievalService, ImageDescriptionService imageDescriptionService,
            int ragThresholdChars) {
        this(fileStore, parser, vectorizationService, retrievalService, imageDescriptionService, ragThresholdChars,
                Clock.systemUTC());
    }

    FileQaService(FileStore fileStore, FileTextParser parser, FileVectorizationService vectorizationService,
            RagRetrievalService retrievalService, ImageDescriptionService imageDescriptionService,
            int ragThresholdChars, Clock clock) {
        this.fileStore = fileStore;
        this.parser = parser;
        this.vectorizationService = vectorizationService;
        this.retrievalService = retrievalService;
        this.imageDescriptionService = imageDescriptionService;
        this.ragThresholdChars = ragThresholdChars;
        this.clock = clock;
    }

    /**
     * 按文件类型解析/落库；文本超过阈值时额外分块入库到向量库；图片只存原始字节，
     * 描述留到第一次被问到再懒加载。
     *
     * @param conversationId 所属会话；跨轮可见，{@code turnId} 要等这一轮结束才回填（issue #28 的范围）
     * @param fileName       原始文件名
     * @param contentType    上传时的 MIME 类型，可能为 null
     * @param content        文件原始字节流，本方法负责读取但不负责关闭——关闭由调用方（HTTP 层）处理
     * @throws VectorizationException 大文件向量化失败
     */
    public IngestedFile ingest(String conversationId, String fileName, String contentType,
            InputStream content, long sizeBytes) {
        return ingest(null, conversationId, fileName, contentType, content, sizeBytes);
    }

    /** 带资源归属的生产入口；userId 在最外层冻结后一路传到持久化。 */
    public IngestedFile ingest(String userId, String conversationId, String fileName, String contentType,
            InputStream content, long sizeBytes) {
        FileKind kind = FileKindDetector.detect(contentType, fileName);

        if (kind == FileKind.IMAGE) {
            byte[] rawBytes = readAllBytes(content);
            long id = fileStore.save(new UploadedFile(
                    null, userId, conversationId, null, fileName, contentType, sizeBytes, FileKind.IMAGE, null, rawBytes,
                    clock.millis()));
            return new IngestedFile(id, fileName, kind, sizeBytes, 0, false);
        }

        String parsedText = parser.parse(content, fileName);
        boolean routedToRag = parsedText.length() > ragThresholdChars;

        long id = fileStore.save(new UploadedFile(
                null, userId, conversationId, null, fileName, contentType, sizeBytes, FileKind.TEXT, parsedText, null,
                clock.millis()));

        if (routedToRag) {
            vectorizationService.vectorize(id, parsedText);
        }

        return new IngestedFile(id, fileName, kind, sizeBytes, parsedText.length(), routedToRag);
    }

    /**
     * 按文件类型返回"喂给模型的内容"：图片懒加载多模态描述（首次调用后写回缓存）；
     * 文本阈值以内直接返回全文；超过阈值时没给问题就提示需要问题，给了问题就跑 RAG 检索。
     *
     * @param question 大文件走检索问答、图片首次识别都不需要这个参数区分——图片描述和问题无关，
     *                 大文件检索才用得上
     * @throws NoSuchElementException fileId 不存在
     */
    public String contentFor(long fileId, String question) {
        UploadedFile file = fileStore.findById(fileId)
                .orElseThrow(() -> new NoSuchElementException("未知的文件标识: " + fileId));

        if (file.kind() == FileKind.IMAGE) {
            return contentForImage(file);
        }

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

    /** HTTP 层在读取正文前调用；资源不存在与不属于当前用户统一按不可见处理。 */
    public boolean belongsToUser(long fileId, String userId) {
        return fileStore.findById(fileId).map(file -> userId != null && userId.equals(file.userId())).orElse(false);
    }

    /**
     * {@link com.agenttrail.loop.tools.FileContentTool} 在读取正文前调用——工具调用层没有
     * "当前登录用户"这个直接可信的边界（模型是在会话内部推理，不是一次独立的 HTTP 请求），
     * 但会话本身是可信的（由 {@code ToolParamInjector} 强制覆盖，模型改不了），所以按会话
     * 而不是按用户限定：只能读当前会话自己上传的文件，防止拿一个别的会话/别人的 fileId 探测内容。
     */
    public boolean belongsToConversation(long fileId, String conversationId) {
        return fileStore.findById(fileId)
                .map(file -> conversationId != null && conversationId.equals(file.conversationId()))
                .orElse(false);
    }

    /** 缓存命中直接返回；未命中才真的调多模态模型，并把结果写回缓存。 */
    private String contentForImage(UploadedFile file) {
        String cached = file.parsedText();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        String description = imageDescriptionService.describe(file.rawBytes(), file.fileName());
        fileStore.updateParsedText(file.id(), description);
        return description;
    }

    private static byte[] readAllBytes(InputStream content) {
        try {
            return content.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
