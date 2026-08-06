package com.agenttrail.loop.tools;

import com.agenttrail.capability.file.FileQaService;
import org.springframework.ai.tool.ToolCallback;

import java.util.NoSuchElementException;

/**
 * 让模型真正读到已上传文件内容的工具——{@code FilePromptFormatter} 之前只在 system prompt
 * 里报文件名和 fileId，模型知道"有这个文件"但没有任何办法拿到内容，这个工具补上那一半。
 *
 * <p>不做成"上传后自动把全文塞进 Prompt"：小文件直接注入全文没问题，但大文件必须走 RAG
 * 检索，检索的 query 就是用户当轮的问题——这个问题在文件上传那一刻根本不存在，只有模型
 * 推理到"要不要看这个文件"的那一步、拿到用户的真实问题时才知道该检索什么。所以只能是
 * 一个模型按需调用的工具，不能是上传时就做完的固定动作。
 *
 * <p>{@code conversation_id} 参数会被 {@link com.agenttrail.loop.core.ToolParamInjector}
 * 用 {@code RunnableParams.toolParams} 里的真实值强制覆盖（见 {@code AgentLoopController}），
 * 模型自己填的值不会被信任——只允许读当前会话自己上传的文件，防止拿一个别的会话/别人的
 * fileId 探测内容。
 */
public final class FileContentTool {

    private final FileQaService fileQaService;

    public FileContentTool(FileQaService fileQaService) {
        this.fileQaService = fileQaService;
    }

    public ToolCallback toolCallback() {
        return new JsonToolCallback("load_file_content", """
                读取当前会话里已上传文件的内容。

                小文件（未超过检索阈值）直接返回解析出的全文。大文件必须提供 question 参数，
                工具内部会做语义检索，只返回和问题相关的片段，不会一次性返回全文——不提供
                question 时，大文件会提示"请携带具体问题"，此时应该根据用户实际问题重新调用
                一次并带上 question，不要凭空回答。

                file_id 从对话上下文里"会话文件"区块获取，不要自己编造。
                """, """
                {"type":"object","properties":{\
                "file_id":{"type":"integer","description":"【必填】要读取的文件 fileId，来自会话文件列表"},\
                "question":{"type":"string","description":"用户的具体问题；大文件走语义检索时需要，小文件可省略"},\
                "conversation_id":{"type":"string","description":"系统自动传入当前会话，不需要手动填写"}},\
                "required":["file_id"]}""",
                args -> load(args.longValue("file_id"), args.text("question"), args.text("conversation_id")));
    }

    private String load(Long fileId, String question, String conversationId) {
        if (fileId == null) {
            return "Error: 缺少必填参数 file_id";
        }
        if (conversationId == null || !fileQaService.belongsToConversation(fileId, conversationId)) {
            return "Error: 文件不存在: " + fileId;
        }
        try {
            return fileQaService.contentFor(fileId, question);
        } catch (NoSuchElementException notFound) {
            return "Error: " + notFound.getMessage();
        }
    }
}
