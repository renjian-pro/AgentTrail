package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.file.FileParsingException;
import com.agenttrail.capability.file.FileQaService;
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
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;

/** HTTP adapter for the File QA ports. Expensive parsing and vectorization run in a worker. */
@RestController
public class FileUploadController {
    private final FileIngestUseCase ingest;
    private final FileContentQueryUseCase contentQuery;
    private final FileIngestTaskWorker taskWorker;
    private final FileUploadPolicy uploadPolicy;
    private final long asyncThresholdBytes;
    private final FileQaService legacyService;

    @Autowired
    public FileUploadController(FileIngestUseCase ingest, FileContentQueryUseCase contentQuery,
            FileIngestTaskWorker taskWorker, FileUploadPolicy uploadPolicy,
            @Value("${agenttrail.file.async-threshold-bytes:1048576}") long asyncThresholdBytes) {
        this.ingest = ingest;
        this.contentQuery = contentQuery;
        this.taskWorker = taskWorker;
        this.uploadPolicy = uploadPolicy;
        this.asyncThresholdBytes = asyncThresholdBytes;
        this.legacyService = null;
    }

    /** Compatibility constructor for callers that still exercise the pre-port service directly. */
    public FileUploadController(FileQaService legacyService) {
        this.ingest = null;
        this.contentQuery = null;
        this.taskWorker = null;
        this.uploadPolicy = FileUploadPolicy.defaults();
        this.asyncThresholdBytes = Long.MAX_VALUE;
        this.legacyService = legacyService;
    }

    @PostMapping("/agent/v1/files")
    public FileUploadResponse upload(@RequestParam("file") MultipartFile file,
            @RequestParam("conversationId") String conversationId) {
        String userId = currentUserId();
        try {
            if (legacyService != null) {
                return FileUploadResponse.from(legacyService.ingest(userId, conversationId,
                        file.getOriginalFilename(), file.getContentType(), file.getInputStream(), file.getSize()));
            }
            uploadPolicy.validate(file.getOriginalFilename(), file.getContentType(), file.getSize());
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
        if (userId == null || (legacyService != null
                ? !legacyService.belongsToUser(fileId, userId)
                : !contentQuery.belongsToUser(fileId, userId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + fileId);
        }
        try {
            String content = legacyService != null ? legacyService.contentFor(fileId, question)
                    : contentQuery.contentFor(fileId, question);
            return new FileContentResponse(fileId, content);
        } catch (NoSuchElementException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }

    @DeleteMapping("/agent/v1/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long fileId) {
        String userId = currentUserId();
        boolean belongs = userId != null && (legacyService != null
                ? legacyService.belongsToUser(fileId, userId)
                : contentQuery.belongsToUser(fileId, userId));
        if (!belongs) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + fileId);
        try {
            if (legacyService != null) legacyService.delete(fileId); else ingest.delete(fileId);
        } catch (NoSuchElementException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }

    private static String currentUserId() {
        try { return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null; }
        catch (RuntimeException noHttpContext) { return null; }
    }
}
