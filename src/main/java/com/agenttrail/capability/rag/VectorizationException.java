package com.agenttrail.capability.rag;

/**
 * 大文件分块向量化失败时抛出（issue #26）——参考实现遇到这种失败是"打个 warn 日志，
 * embed 标记保持 0，悄悄继续走直接加载"，结果就是 RAG 检索永远查不到东西却没有任何痕迹。
 * 这里选择让失败直接终止上传，调用方能看到明确的失败响应，而不是一个看似成功、
 * 实际检索不到内容的"半成品"文件记录。
 */
public class VectorizationException extends RuntimeException {

    public VectorizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
