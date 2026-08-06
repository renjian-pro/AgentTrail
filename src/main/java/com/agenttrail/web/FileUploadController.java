package com.agenttrail.web;

import com.agenttrail.capability.file.FileParsingException;
import com.agenttrail.capability.file.FileQaService;
import com.agenttrail.capability.rag.VectorizationException;
import cn.dev33.satoken.stp.StpUtil;
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
                    currentUserId(), conversationId, file.getOriginalFilename(), file.getContentType(),
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
        // Fail-closed: no userId (unauthenticated, or no Sa-Token request context at all) means
        // no access — it must NOT be treated as "skip the ownership check". The global interceptor
        // is expected to reject unauthenticated requests before they ever reach here, but this
        // check has to hold on its own merit and not rely on that assumption never breaking.
        String userId = currentUserId();
        if (userId == null || !fileQaService.belongsToUser(fileId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + fileId);
        }
        try {
            return new FileContentResponse(fileId, fileQaService.contentFor(fileId, question));
        } catch (NoSuchElementException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }

    /**
     * 删除一个已上传文件——之前前端"×"按钮只是把它从本地列表里过滤掉，服务端这行 {@code agent_file}
     * 和它在向量库里的分块从没被真的删过，会话里继续提问时模型和 RAG 检索照样能看到它，
     * "删除"只是前端幻觉。这里补上真正的删除入口，联动清理见 {@link FileQaService#delete}。
     */
    @DeleteMapping("/agent/v1/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long fileId) {
        String userId = currentUserId();
        if (userId == null || !fileQaService.belongsToUser(fileId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + fileId);
        }
        try {
            fileQaService.delete(fileId);
        } catch (NoSuchElementException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
    }

    private static String currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        } catch (RuntimeException noHttpContext) {
            // 纯单元测试直接调用 Controller 时没有 Sa-Token 请求上下文；真实 HTTP 请求会被拦截器保护。
            return null;
        }
    }
}
