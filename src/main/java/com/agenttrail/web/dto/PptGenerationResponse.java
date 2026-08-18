package com.agenttrail.web.dto;

import com.agenttrail.capability.ppt.PptState;

/**
 * PPT 生成的 HTTP 入口响应体（issue #24）——{@code taskId} 是恢复用的凭证（见 {@code /resume}）。
 *
 * <p>{@code clarifyingQuestion} 只在 {@code status} 为 {@link PptState#AWAITING_INPUT} 时非空：
 * 状态机判定需求不够清晰，停下来等用户补充。它**不是错误**，和 {@code errorMsg} 互斥——
 * 前端据此渲染一个回答框（{@code POST /agent/v1/ppt/clarify/{taskId}}）而不是一条报错。
 */
public record PptGenerationResponse(long taskId, PptState status, String errorMsg, String outputPath,
                                    String clarifyingQuestion) {

    /** 兼容早于需求澄清的四参数调用（测试与旧装配），语义等价于"这条任务没有待回答的追问"。 */
    public PptGenerationResponse(long taskId, PptState status, String errorMsg, String outputPath) {
        this(taskId, status, errorMsg, outputPath, null);
    }
}
