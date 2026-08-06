package com.agenttrail.capability.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlapParagraphTextSplitterTest {

    @Test
    void returnsNoChunksForBlankInput() {
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(10, 2);

        assertThat(splitter.apply(List.of(new Document("   ")))).isEmpty();
    }

    @Test
    void shortTextFitsInASingleChunk() {
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(100, 10);

        List<Document> chunks = splitter.apply(List.of(new Document("hello world")));

        assertThat(chunks).extracting(Document::getText).containsExactly("hello world");
    }

    @Test
    void splitsLongTextIntoChunksOfTheConfiguredSize() {
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(10, 0);
        String text = "a".repeat(25);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).extracting(Document::getText)
                .containsExactly("a".repeat(10), "a".repeat(10), "a".repeat(5));
    }

    @Test
    void consecutiveChunksShareTheConfiguredOverlap() {
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(10, 3);
        String text = "a".repeat(20);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        // 第二块的开头 3 个字符必须和第一块的结尾 3 个字符一致——这就是 overlap 的意义
        String firstChunk = chunks.get(0).getText();
        String secondChunk = chunks.get(1).getText();
        assertThat(secondChunk).startsWith(firstChunk.substring(firstChunk.length() - 3));
    }

    @Test
    void rejectsANonPositiveChunkSize() {
        assertThatThrownBy(() -> new OverlapParagraphTextSplitter(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeOverlap() {
        assertThatThrownBy(() -> new OverlapParagraphTextSplitter(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnOverlapThatIsNotSmallerThanChunkSize() {
        assertThatThrownBy(() -> new OverlapParagraphTextSplitter(10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsNoChunksForAnEmptyDocumentList() {
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(10, 2);

        assertThat(splitter.apply(List.of())).isEmpty();
    }
}
