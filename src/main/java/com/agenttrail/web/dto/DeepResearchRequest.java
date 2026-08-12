package com.agenttrail.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /agent/v1/deepresearch} 的请求体。
 *
 * <p>{@code previousQuestion}/{@code previousClarifyingQuestion} 非空时表示这是对上一次澄清追问
 * 的回复——{@code question} 此时是用户的回复原文，两个 previous 字段由前端从上一轮的
 * {@code DeepResearchReport} 里原样带回来，不依赖服务端保存任何跨请求状态。
 */
public record DeepResearchRequest(
        @Size(max = 100) String conversationId,
        @NotBlank @Size(max = 20000) String question,
        @Size(max = 20000) String previousQuestion,
        @Size(max = 20000) String previousClarifyingQuestion) {
}
