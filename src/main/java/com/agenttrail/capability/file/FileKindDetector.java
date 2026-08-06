package com.agenttrail.capability.file;

import java.util.Locale;
import java.util.Set;

/** 按 MIME 类型（优先）或文件扩展名判定 {@link FileKind}（issue #27）。 */
public final class FileKindDetector {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    private FileKindDetector() {
    }

    public static FileKind detect(String contentType, String fileName) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return FileKind.IMAGE;
        }
        return IMAGE_EXTENSIONS.contains(extensionOf(fileName)) ? FileKind.IMAGE : FileKind.TEXT;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
