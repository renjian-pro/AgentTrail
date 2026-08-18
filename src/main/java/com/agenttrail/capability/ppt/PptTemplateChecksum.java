package com.agenttrail.capability.ppt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 模板/素材的内容指纹工具；恢复和发布校验都使用 SHA-256，而不是文件名。 */
public final class PptTemplateChecksum {
    private PptTemplateChecksum() {
    }

    public static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest.digest()) result.append(String.format("%02x", b));
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new PptGenerationException("计算 PPT 模板 checksum 失败", failure);
        }
    }
}
