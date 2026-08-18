package com.agenttrail.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 基于已成功 PPT 创建新版本的修改请求。
 *
 * <p>baseTaskId 放在路径中，避免客户端同时提交两个可能不一致的任务编号；
 * idempotencyKey 让网络重试只创建一个修改版本。</p>
 */
public record PptModifyRequest(
        @NotBlank @Size(max = 20000) String message,
        @Size(max = 200) String idempotencyKey) {

    public PptModifyRequest(String message) {
        this(message, null);
    }
}
