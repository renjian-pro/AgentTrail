package com.agenttrail.platform.model;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AgentTrail 所有文本生成链路共用的模型标识。
 *
 * <p>供应商客户端、普通对话、数据分析、深度研究和 PPT 编排都从这里取值，避免每项能力
 * 各自维护一份默认模型并在改模型时产生静默分流。多模态、向量和文生图不是文本生成模型，
 * 仍保留各自独立配置。
 */
@Validated
@ConfigurationProperties(prefix = "agenttrail.model")
public record AgentModelProperties(@NotBlank String id) {
}
