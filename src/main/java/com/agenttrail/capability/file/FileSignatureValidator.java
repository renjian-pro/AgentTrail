package com.agenttrail.capability.file;

import java.util.Map;

/**
 * 客户端提供的 {@code Content-Type} 是可伪造的（浏览器/前端随便传一个字符串），单靠它做类型白名单
 * 挡不住"把可执行文件重命名成 .pdf 上传"这类绕过。这里只对有稳定二进制文件头的类型做校验——纯文本类
 * 类型（{@code text/*}/{@code application/json}/{@code application/xml}/{@code application/rtf}）
 * 没有可靠的魔数，交给已有的 {@link FileUploadPolicy} 白名单和后续解析阶段的失败处理，不在这里
 * 强行发明一套不可靠的启发式。
 */
final class FileSignatureValidator {

    private static final Map<String, byte[][]> SIGNATURES_BY_CONTENT_TYPE = Map.ofEntries(
            Map.entry("application/pdf", new byte[][]{{0x25, 0x50, 0x44, 0x46}}), // %PDF
            Map.entry("image/png", new byte[][]{{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}}),
            Map.entry("image/jpeg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}}),
            Map.entry("image/gif", new byte[][]{{0x47, 0x49, 0x46, 0x38, 0x37, 0x61}, {0x47, 0x49, 0x46, 0x38, 0x39, 0x61}}),
            Map.entry("image/bmp", new byte[][]{{0x42, 0x4D}}),
            Map.entry("image/webp", new byte[][]{{0x52, 0x49, 0x46, 0x46}}), // RIFF....WEBP, header check only covers RIFF
            // Legacy OLE-compound-file Office formats (.doc/.xls/.ppt) share one container signature.
            Map.entry("application/msword", new byte[][]{{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}}),
            Map.entry("application/vnd.ms-excel", new byte[][]{{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}}),
            Map.entry("application/vnd.ms-powerpoint", new byte[][]{{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}}),
            // OOXML formats (.docx/.xlsx/.pptx) are zip containers.
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    new byte[][]{{0x50, 0x4B, 0x03, 0x04}}),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    new byte[][]{{0x50, 0x4B, 0x03, 0x04}}),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    new byte[][]{{0x50, 0x4B, 0x03, 0x04}}));

    private FileSignatureValidator() {
    }

    /** {@code true} 表示头部字节匹配声明的类型，或者该类型没有已知的二进制签名（放行由白名单负责）。 */
    static boolean matches(String contentType, byte[] header) {
        if (contentType == null) {
            return false;
        }
        byte[][] candidates = SIGNATURES_BY_CONTENT_TYPE.get(contentType.toLowerCase(java.util.Locale.ROOT));
        if (candidates == null) {
            return true;
        }
        for (byte[] signature : candidates) {
            if (startsWith(header, signature)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] header, byte[] signature) {
        if (header == null || header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
