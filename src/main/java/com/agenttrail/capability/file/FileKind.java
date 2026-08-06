package com.agenttrail.capability.file;

/**
 * 上传文件按内容路由到哪条问答链路（issue #27）。
 *
 * <ul>
 *   <li>{@link #TEXT}——Tika 解析，超阈值再走 RAG 检索（issue #21/#26）
 *   <li>{@link #IMAGE}——不解析、不进 RAG，懒加载调多模态模型描述图片，结果写回缓存
 * </ul>
 */
public enum FileKind {
    TEXT,
    IMAGE
}
