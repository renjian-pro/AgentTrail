package com.agenttrail.web.dto;

import com.agenttrail.capability.ppt.PptState;

/** PPT 生成的 HTTP 入口响应体（issue #24）——{@code taskId} 是恢复用的凭证（见 {@code /resume}）。 */
public record PptGenerationResponse(long taskId, PptState status, String errorMsg, String outputPath) {
}
