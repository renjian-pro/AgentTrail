package com.agenttrail.capability.ppt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 由任务、页面、字段和内容摘要派生稳定 object key，重试不会无界制造随机对象。 */
public final class PptAssetKey {
    private PptAssetKey() {
    }

    public static String derive(String taskScope, String pageId, String fieldName, String content) {
        String digest = sha256(content == null ? "" : content).substring(0, 24);
        return "ppt/" + safe(taskScope) + "/" + safe(pageId) + "/" + safe(fieldName) + "/" + digest;
    }

    public static String digest(String content) {
        return sha256(content == null ? "" : content);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM lacks SHA-256", impossible);
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
