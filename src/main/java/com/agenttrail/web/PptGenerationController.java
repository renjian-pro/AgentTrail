package com.agenttrail.web;

import com.agenttrail.loop.ppt.PptGenerationService;
import com.agenttrail.loop.ppt.PptTask;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * PPT 生成的 HTTP 入口（issue #24）。同步接口，和 {@code DeepResearchController} 一样——
 * 一次请求跑完整个状态机才返回，不做进度推送（那是这个"最小骨架"票之外的事）。
 *
 * <p>{@code /resume/{taskId}} 单独暴露断点续传：如果上一次 {@code /create} 因为某个状态失败
 * 而抛了异常，DB 里的任务停在那个失败的状态上，调用这个接口会从同一个状态重新跑，不是从头开始。
 */
@RestController
public class PptGenerationController {

    private final PptGenerationService pptGenerationService;
    private final CapabilityConversationService conversationService;

    public PptGenerationController(PptGenerationService pptGenerationService,
            CapabilityConversationService conversationService) {
        this.pptGenerationService = pptGenerationService;
        this.conversationService = conversationService;
    }

    @PostMapping("/agent/v1/ppt/create")
    public PptGenerationResponse create(@RequestBody PptGenerationRequest request) {
        long startedAt = System.nanoTime();
        try {
            long taskId = pptGenerationService.create(request.conversationId(), request.message());
            PptGenerationResponse response = toResponse(taskId);
            conversationService.recordSuccess(request.conversationId(), request.message(),
                    "PPT 任务状态：" + response.status(), "ppt", response, elapsedMillis(startedAt));
            return response;
        } catch (RuntimeException failure) {
            conversationService.recordFailure(request.conversationId(), request.message(), "ppt",
                    failure.getMessage(), elapsedMillis(startedAt));
            throw failure;
        }
    }

    @PostMapping("/agent/v1/ppt/resume/{taskId}")
    public PptGenerationResponse resume(@PathVariable long taskId) {
        pptGenerationService.run(taskId);
        return toResponse(taskId);
    }

    /**
     * 浏览器不能也不应直接访问服务器文件系统路径。只暴露任务号构成的受控下载地址，真实产物路径
     * 始终留在服务端；产物被清理或任务不存在时返回 404，而不是生成一个点击无反应的伪链接。
     */
    @GetMapping(value = "/agent/v1/ppt/{taskId}/download",
            produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    public ResponseEntity<Resource> download(@PathVariable long taskId) {
        Path output = outputFileOf(taskId);
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    .contentLength(Files.size(output))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(output.getFileName().toString(), StandardCharsets.UTF_8)
                            .build().toString())
                    .body(new FileSystemResource(output));
        } catch (IOException readFailure) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 文件不可读取: " + taskId, readFailure);
        }
    }

    private PptGenerationResponse toResponse(long taskId) {
        PptTask task = pptGenerationService.describe(taskId)
                .orElseThrow(() -> new IllegalStateException("PPT 任务不存在: " + taskId));
        String downloadUrl = task.status() == com.agenttrail.loop.ppt.PptState.SUCCESS
                && hasOutputFile(taskId)
                ? "/agent/v1/ppt/" + taskId + "/download"
                : null;
        return new PptGenerationResponse(taskId, task.status(), task.errorMsg(), downloadUrl);
    }

    private boolean hasOutputFile(long taskId) {
        try {
            return Files.isRegularFile(Path.of(pptGenerationService.outputPathOf(taskId)));
        } catch (InvalidPathException | NullPointerException ignored) {
            return false;
        }
    }

    private Path outputFileOf(long taskId) {
        String outputPath = pptGenerationService.outputPathOf(taskId);
        try {
            Path output = outputPath == null ? null : Path.of(outputPath).toAbsolutePath().normalize();
            if (output == null || !Files.isRegularFile(output)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 文件不存在: " + taskId);
            }
            return output;
        } catch (InvalidPathException invalidPath) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 文件不存在: " + taskId, invalidPath);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
