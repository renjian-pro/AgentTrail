package com.agenttrail.web.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param conversationId   会话标识；首次请求不传时由服务端创建，后续轮次必须原样带回
 * @param webSearchEnabled 这次对话要不要挂联网搜索工具（issue #22）。不传按 false 处理——
 *                         "条件工具"的语义是关闭时工具列表里压根没有它，不是默认打开
 * @param mode             前端选中的能力模式，取值见 {@code CapabilityMode}（issue #106）。
 *                         不传/空白按 {@code chat} 处理；**未注册的取值返回 400**，不再静默降级；
 *                         {@code research}/{@code ppt} 也返回 400——它们走各自的任务接口，
 *                         出现在这里就是前端路由错了
 * @param fileIds          这一轮要附带的文件（issue #110 / R21）。**上传和"这一轮附了什么"是两件事**：
 *                         上传只是把文件存下来，真正的绑定发生在用户按下发送、把 id 显式带上来的
 *                         这一刻。此前后端是"扫一遍这个会话里所有还没归属轮次的文件"，于是"发送前
 *                         删掉"只是前端幻觉、两个标签页会互相吞文件、上传完没发就走人下次随便问
 *                         一句都会把它带上。不传按空列表处理
 */
public record AgentChatRequest(
        @NotBlank @Size(max = 20000) String message,
        @Size(max = 100) String conversationId,
        boolean webSearchEnabled,
        @Size(max = 50) String mode,
        @Size(max = 50) List<Long> fileIds) {

    public AgentChatRequest {
        fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
    }

    /** 不带附件的简写——Jackson 走 canonical 构造，这个只服务于直接 new 的调用方。 */
    public AgentChatRequest(String message, String conversationId, boolean webSearchEnabled, String mode) {
        this(message, conversationId, webSearchEnabled, mode, List.of());
    }

}
