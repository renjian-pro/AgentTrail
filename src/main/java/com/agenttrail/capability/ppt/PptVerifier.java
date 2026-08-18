package com.agenttrail.capability.ppt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * 渲染后硬门禁：重新打开 OOXML、检查 slide 数量和关键包关系，避免“文件存在”被误当成 SUCCESS。
 * 视觉模型检查可以在此结果之上扩展，但不能替代这些确定性检查。
 */
public final class PptVerifier {
    public PptVerificationResult verify(Path output, PptSchema schema) {
        if (output == null || !Files.isRegularFile(output)) {
            throw new PptGenerationException("PPT 产物不存在，不能进入 SUCCESS");
        }
        try {
            long size = Files.size(output);
            if (size <= 0) throw new PptGenerationException("PPT 产物为空");
            try (ZipFile zip = new ZipFile(output.toFile())) {
                if (zip.getEntry("ppt/presentation.xml") == null || zip.getEntry("[Content_Types].xml") == null) {
                    throw new PptGenerationException("PPT 产物不是有效的 OOXML 文件");
                }
                int slides = (int) zip.stream().filter(entry -> entry.getName().matches("ppt/slides/slide\\d+\\.xml"))
                        .count();
                int expected = schema != null && !schema.pages().isEmpty()
                        ? schema.pages().size() : 1 + (schema == null || schema.contentSlides() == null
                                ? 0 : schema.contentSlides().size());
                if (slides != expected) {
                    throw new PptGenerationException("PPT 页数校验失败，期望 " + expected + " 页，实际 " + slides + " 页");
                }
                return new PptVerificationResult(PptTemplateChecksum.sha256(output), size, slides, List.of());
            }
        } catch (IOException failure) {
            throw new PptGenerationException("重新打开 PPT 产物失败", failure);
        }
    }
}
