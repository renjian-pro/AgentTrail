package com.agenttrail.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户回答 PPT 需求澄清追问的请求体。
 *
 * <p>刻意不带 conversationId：要续的是哪一条任务由路径上的 {@code taskId} 唯一确定，
 * 归属校验走登录用户，再让前端传一次会话 id 只会多出一个可以和 taskId 对不上的参数。
 */
public record PptClarifyRequest(@NotBlank @Size(max = 20000) String answer) {
}
