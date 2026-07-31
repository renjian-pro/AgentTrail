package com.agenttrail.web;

import com.agenttrail.loop.file.FileParsingException;
import com.agenttrail.loop.file.FileQaService;
import com.agenttrail.loop.rag.VectorizationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;

/**
 * 文件上传与问答的 HTTP 入口（issue #21）。上传时立刻用 Tika 解析并落库，取内容按字符数阈值
 * 分流——阈值以内是解析出的全量文本，超过阈值是"走检索问答"的占位提示（真正的检索管线是
 * issue #26 的范围）。
 *
 * <p>这里没有把解析结果自动接进某个会话正在跑的对话——那是"多轮文件生命周期与 system prompt
 * 分组渲染"（issue #28）要做的事，这一票只把"上传 → 解析 → 按阈值取内容"这条链路建好。
 */
@RestController
public class FileUploadController {

    private final FileQaService fileQaService;

    public FileUploadController(FileQaService fileQaService) {
        this.fileQaService = fileQaService;
    }

    @PostMapping("/agent/v1/files")
    public FileUploadResponse upload(@RequestParam("file") MultipartFile file,
            @RequestParam("conversationId") String conversationId) {
        try {
            return FileUploadResponse.from(fileQaService.ingest(
                    conversationId, file.getOriginalFilename(), file.getContentType(),
                    file.getInputStream(), file.getSize()));
        } catch (IOException readFailure) {
            throw new UncheckedIOException(readFailure);
        } catch (FileParsingException parseFailure) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, parseFailure.getMessage(), parseFailure);
        } catch (VectorizationException vectorizationFailure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, vectorizationFailure.getMessage(), vectorizationFailure);
        }
    }

    /**
     * @param question 大文件（走 RAG）问答用的问题；小文件忽略这个参数，不传也不报错
     */
    @GetMapping("/agent/v1/files/{fileId}/content")
    public FileContentResponse content(@PathVariable long fileId,
            @RequestParam(name = "question", required = false) String question) {
        try {
            return new FileContentResponse(fileId, fileQaService.contentFor(fileId, question));
        } catch (NoSuchElementException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }
}
