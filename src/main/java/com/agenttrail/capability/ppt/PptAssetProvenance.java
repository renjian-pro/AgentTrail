package com.agenttrail.capability.ppt;

/** 外部素材来源的稳定审计信息；不保存短时签名 URL 作为唯一引用。 */
public record PptAssetProvenance(PptAssetSource source, String sourceUrl, String license,
        String mimeType, long sizeBytes, int width, int height) {
    public PptAssetProvenance {
        if (source == null) {
            throw new IllegalArgumentException("PPT asset source is required");
        }
        if (sizeBytes < 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("PPT asset dimensions and size must not be negative");
        }
    }
}
