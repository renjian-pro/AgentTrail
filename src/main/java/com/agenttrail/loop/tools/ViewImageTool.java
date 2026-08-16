package com.agenttrail.loop.tools;

import com.agenttrail.capability.file.FileQaService;
import org.springframework.ai.tool.ToolCallback;

import java.util.NoSuchElementException;

/**
 * 带着具体问题重新看一张已上传的图片（issue #105）。
 *
 * <p><b>为什么是工具而不是把整个对话升级成多模态</b>：主对话必须支持 tool calling，而带工具的
 * 请求已经因为客户端兼容性问题被强制路由到 {@code deepseek-chat}（踩坑点 #78a），那个模型不带视觉。
 * 要多模态就得放弃工具调用，两者当前直接互斥。与其二选一，不如让视觉模型作为一次工具调用被按需
 * 触发——这和 {@code load_file_content} 处理大文件是同一个模式：不预先把全部内容塞进上下文，
 * 而是带着真实问题按需取。
 *
 * <p><b>和缓存描述的分工</b>：{@code load_file_content} 对图片返回的是一次性生成、缓存下来的通用
 * 描述，便宜且可进 RAG，泛泛的问题用它就够。但描述没覆盖到的细节（"左下角那个数字是多少"），
 * 模型答不出来还不知道自己没看过原图，于是会编——那正是这个工具存在的理由。
 *
 * <p>{@code conversation_id} 由 {@link com.agenttrail.loop.core.ToolParamInjector} 强制覆盖，
 * 模型填的值不被信任：只允许看当前会话自己上传的图，防止拿别的会话的 fileId 探测内容。
 */
public final class ViewImageTool {

    private final FileQaService fileQaService;

    public ViewImageTool(FileQaService fileQaService) {
        this.fileQaService = fileQaService;
    }

    public ToolCallback toolCallback() {
        return new JsonToolCallback("view_image", """
                带着一个具体问题重新查看当前会话里已上传的图片，返回针对该问题的回答。

                什么时候用：load_file_content 给出的图片描述里没有你需要的细节时（例如某个具体数字、
                某个角落的文字、颜色或位置关系）。不要凭描述推测图中没提到的内容。

                question 必填且要具体——问"这是什么图"不如问"图中柱状图最高的那一根是哪个月份"。
                file_id 从对话上下文里"会话文件"区块获取，只能是标注为图片的那些。
                """, """
                {"type":"object","properties":{\
                "file_id":{"type":"integer","description":"【必填】要查看的图片 fileId，来自会话文件列表"},\
                "question":{"type":"string","description":"【必填】关于这张图的具体问题"},\
                "conversation_id":{"type":"string","description":"系统自动传入当前会话，不需要手动填写"}},\
                "required":["file_id","question"]}""",
                args -> view(args.longValue("file_id"), args.text("question"), args.text("conversation_id")));
    }

    private String view(Long fileId, String question, String conversationId) {
        if (fileId == null) {
            return "Error: 缺少必填参数 file_id";
        }
        if (question == null || question.isBlank()) {
            return "Error: 缺少必填参数 question——这个工具的价值就在于带着具体问题重新看图，"
                    + "只想要一段泛泛的描述请改用 load_file_content";
        }
        if (conversationId == null || !fileQaService.belongsToConversation(fileId, conversationId)) {
            return "Error: 文件不存在: " + fileId;
        }
        try {
            return fileQaService.answerAboutImage(fileId, question);
        } catch (NoSuchElementException notFound) {
            return "Error: " + notFound.getMessage();
        }
    }
}
