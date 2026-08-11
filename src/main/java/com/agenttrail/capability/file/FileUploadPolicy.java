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
}
