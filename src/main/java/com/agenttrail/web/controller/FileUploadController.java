package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.file.FileParsingException;
import com.agenttrail.capability.file.FileUploadPolicy;
import com.agenttrail.capability.fileqa.application.FileContentQueryUseCase;
import com.agenttrail.capability.fileqa.application.FileIngestTaskWorker;
import com.agenttrail.capability.fileqa.application.FileIngestUseCase;
import com.agenttrail.capability.rag.VectorizationException;
import com.agenttrail.web.dto.FileContentResponse;
import com.agenttrail.web.dto.FileUploadResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;

/**
 * HTTP adapter for the File QA ports. Expensive parsing and vectorization run in a worker.
 *
 * <p>只有一条路径。这里原先还有一个接 {@code FileQaService} 的兼容构造函数，配合三处
 * {@code legacyService != null ? ... : ...} 三元分支——生产从不走它，但
 * {@code FileUploadControllerTest} 的全部用例（包括两个 IDOR 回归和一个 fail-open 回归）
 * 恰恰跑在那条分支上，等于安全断言守着一条没人执行的代码。Phase -1 删掉兼容分支，
 * 测试改造到生产路径，让那些回归断言真正保护线上行为。
 */
@RestController
public class FileUploadController {
    private final FileIngestUseCase ingest;
    private final FileContentQueryUseCase contentQuery;
    private final FileIngestTaskWorker taskWorker;
    private final FileUploadPolicy uploadPolicy;
    private final long asyncThresholdBytes;

    @Autowired
    public FileUploadController(FileIngestUseCase ingest, FileContentQueryUseCase contentQuery,
            FileIngestTaskWorker taskWorker, FileUploadPolicy uploadPolicy,
            @Value("${agenttrail.file.async-threshold-bytes:1048576}") long asyncThresholdBytes) {
        this.ingest = ingest;
        this.contentQuery = contentQuery;
        this.taskWorker = taskWorker;
        this.uploadPolicy = uploadPolicy;
        this.asyncThresholdBytes = asyncThresholdBytes;
    }

    @PostMapping("/agent/v1/files")
    public FileUploadResponse upload(@RequestParam("file") MultipartFile file,
            @RequestParam("conversationId") String conversationId) {
        String userId = currentUserId();
        try {
            uploadPolicy.validate(file.getOriginalFilename(), file.getContentType(), file.getSize());
            uploadPolicy.validateSignature(file.getContentType(), readHeader(file));
            if (file.getSize() > asyncThresholdBytes) {
                byte[] bytes = file.getBytes();
                FileIngestUseCase.ReservedUpload reserved = ingest.reserve(userId, conversationId,
                        file.getOriginalFilename(), file.getContentType(), file.getSize());
                String taskId = taskWorker.submit(reserved, bytes);
                return FileUploadResponse.pending(reserved.fileId(), reserved.fileName(), reserved.kind(),
                        reserved.sizeBytes(), taskId);
            }
            return FileUploadResponse.from(ingest.ingest(userId, conversationId, file.getOriginalFilename(),
                    file.getContentType(), new ByteArrayInputStream(file.getBytes()), file.getSize()));
        } catch (IOException readFailure) {
            throw new UncheckedIOException(readFailure);
        } catch (FileParsingException parseFailure) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, parseFailure.getMessage(), parseFailure);
        } catch (VectorizationException vectorizationFailure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, vectorizationFailure.getMessage(), vectorizationFailure);
        } catch (IllegalArgumentException rejected) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, rejected.getMessage(), rejected);
        }
    }

    @GetMapping("/agent/v1/files/{fileId}/content")
    public FileContentResponse content(@PathVariable long fileId,
            @RequestParam(name = "question", required = false) String question) {
        String userId = currentUserId();
        // userId 为 null 必须直接拒绝，不能跳过归属校验——那是 fail-open，见对应回归测试
        if (userId == null || !contentQuery.belongsToUser(fileId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + fileId);
        }
        try {
            return new FileContentResponse(fileId, contentQuery.contentFor(fileId, question));
        } catch (NoSuchElementException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }

    @DeleteMapping("/agent/v1/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long fileId) {
        String userId = currentUserId();
        if (userId == null || !contentQuery.belongsToUser(fileId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + fileId);
        }
        try {
            ingest.delete(fileId);
        } catch (NoSuchElementException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }

    /** 只嗅探开头几个字节做文件签名校验，不影响后续 {@code file.getBytes()}/{@code getInputStream()} 的完整读取。 */
    private static byte[] readHeader(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(16);
        }
    }

    private static String currentUserId() {
        try { return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null; }
        catch (RuntimeException noHttpContext) { return null; }
    }
}
