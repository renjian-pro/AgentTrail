package com.agenttrail.capability.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按段落（{@code \n+}）切分 + 块内按 chunkSize 续写 + 块间 overlap 重叠的文本切分器（issue #26）。
 *
 * <p>优先保持自然段边界；超长段落按 chunkSize 续写，并用 overlap 保留跨块语义。
 * 构造参数约束用于避免空块、负重叠和无法推进的切分循环。
 */
public class OverlapParagraphTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int overlap;

    public OverlapParagraphTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 不能大于等于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        String[] paragraphs = text.split("\\n+");
        List<String> allChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) {
                continue;
            }

            int start = 0;
            while (start < paragraph.length()) {
                int remainingSpace = chunkSize - currentChunk.length();
                int end = Math.min(start + remainingSpace, paragraph.length());

                currentChunk.append(paragraph, start, end);

                if (currentChunk.length() >= chunkSize) {
                    allChunks.add(currentChunk.toString());

                    String overlapText = "";
                    if (overlap > 0) {
                        int overlapStart = Math.max(0, currentChunk.length() - overlap);
                        overlapText = currentChunk.substring(overlapStart);
                    }

                    currentChunk = new StringBuilder();
                    if (!overlapText.isEmpty()) {
                        currentChunk.append(overlapText);
                    }
                }

                start = end;
            }
        }

        if (currentChunk.length() > 0) {
            allChunks.add(currentChunk.toString());
        }

        return allChunks;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            for (String chunk : splitText(doc.getText())) {
                result.add(new Document(chunk));
            }
        }
        return result;
    }
}
