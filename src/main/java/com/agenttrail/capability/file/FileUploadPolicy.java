package com.agenttrail.capability.file;

import java.util.Locale;
import java.util.Set;

/** Fast, record-free validation performed before an attachment is persisted. */
public record FileUploadPolicy(long maxBytes, Set<String> allowedContentTypes) {
    public FileUploadPolicy {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        allowedContentTypes = allowedContentTypes == null ? Set.of() : Set.copyOf(allowedContentTypes);
    }

    public static FileUploadPolicy defaults() {
        return new FileUploadPolicy(50L * 1024 * 1024, Set.of(
                "application/pdf", "application/json", "application/xml", "text/plain", "text/csv",
                "text/markdown", "text/html", "application/rtf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "image/jpeg", "image/png", "image/gif", "image/bmp", "image/webp"));
    }

    public void validate(String fileName, String contentType, long sizeBytes) {
        if (sizeBytes < 0 || sizeBytes > maxBytes) {
            throw new FileUploadRejectedException("文件大小超过限制: " + maxBytes + " bytes");
        }
        if (contentType == null || !allowedContentTypes.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new FileUploadRejectedException("不支持的文件类型: " + contentType);
        }
    }

    /**
     * 客户端声明的 {@code Content-Type} 是可伪造的——只校验白名单挡不住"可执行文件改名成 .pdf"
     * 这类绕过。{@code header} 是文件开头的若干字节（够覆盖已知最长签名即可，见
     * {@link FileSignatureValidator}）；调用方需要在 {@link #validate} 之后单独调这个方法。
     */
    public void validateSignature(String contentType, byte[] header) {
        if (!FileSignatureValidator.matches(contentType, header)) {
            throw new FileUploadRejectedException("文件内容和声明的类型不一致: " + contentType);
        }
    }
}
