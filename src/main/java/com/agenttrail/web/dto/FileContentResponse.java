package com.agenttrail.web.dto;

/** {@code GET /agent/v1/files/{fileId}/content} 的响应体——{@code content} 就是模型会看到的内容。 */
public record FileContentResponse(long fileId, String content) {
}
