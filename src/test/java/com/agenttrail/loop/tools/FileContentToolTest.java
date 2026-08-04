package com.agenttrail.loop.tools;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.file.FileQaService;
import com.agenttrail.loop.file.FileTextParser;
import com.agenttrail.loop.file.IngestedFile;
import com.agenttrail.loop.file.InMemoryFileStore;
import com.agenttrail.loop.multimodal.ImageDescriptionService;
import com.agenttrail.loop.multimodal.support.RecordingSyncChatModel;
import com.agenttrail.loop.rag.FileVectorizationService;
import com.agenttrail.loop.rag.RagRetrievalService;
import com.agenttrail.loop.rag.support.RecordingVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证模型能通过这个工具真正读到文件内容——这是 {@code FilePromptFormatter} 只报文件名、
 * 模型此前完全没有办法获取内容这个缺口的另一半。
 */
class FileContentToolTest {

    private static final int RAG_THRESHOLD = 20;

    private final RecordingVectorStore vectorStore = new RecordingVectorStore();
    private final FileQaService fileQaService = new FileQaService(new InMemoryFileStore(), new FileTextParser(),
            new FileVectorizationService(vectorStore), new RagRetrievalService(vectorStore, new ScriptedChatModel()),
            new ImageDescriptionService(new RecordingSyncChatModel(), "vl-model"), RAG_THRESHOLD);
    private final ToolCallback tool = new FileContentTool(fileQaService).toolCallback();

    @Test
    void returnsTheFullTextForASmallFileWhenTheCallerOwnsTheConversation() {
        long fileId = ingest("conv-1", "hello world");

        String result = tool.call("""
                {"file_id": %d, "conversation_id": "conv-1"}""".formatted(fileId));

        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void asksForAQuestionWhenALargeFileIsRequestedWithoutOne() {
        long fileId = ingest("conv-1", "a".repeat(RAG_THRESHOLD + 1));

        String result = tool.call("""
                {"file_id": %d, "conversation_id": "conv-1"}""".formatted(fileId));

        assertThat(result).contains("请携带具体问题");
    }

    // 大文件 + 真实 question 会触发 RagRetrievalService 里的 CompressionQueryTransformer/
    // MultiQueryExpander，这两步要求 ChatModel 支持同步 ChatClient 调用——项目里现有的两个
    // ChatModel 测试替身都不满足这个条件（ScriptedChatModel 只支持流式、RecordingSyncChatModel
    // 没有可用的 ChatOptions），FileQaServiceTest 自己也明确注释过同样绕开了这条路径
    // （"这里的 RAG 测试都不带问题……这个 ChatModel 永远不会真正被调用"）。这个工具类只负责
    // 路由和转发，真正的检索正确性不是它的职责，不在这里补一个和现有测试基建对不上的用例。

    @Test
    void rejectsAFileThatBelongsToAnotherConversation() {
        long fileId = ingest("conv-1", "hello world");

        String result = tool.call("""
                {"file_id": %d, "conversation_id": "conv-2"}""".formatted(fileId));

        assertThat(result).contains("Error").contains("文件不存在");
    }

    @Test
    void rejectsWhenConversationIdIsMissing() {
        long fileId = ingest("conv-1", "hello world");

        String result = tool.call("""
                {"file_id": %d}""".formatted(fileId));

        assertThat(result).contains("Error").contains("文件不存在");
    }

    @Test
    void rejectsAnUnknownFileId() {
        String result = tool.call("""
                {"file_id": 999, "conversation_id": "conv-1"}""");

        assertThat(result).contains("Error");
    }

    @Test
    void rejectsWhenFileIdIsMissing() {
        String result = tool.call("""
                {"conversation_id": "conv-1"}""");

        assertThat(result).contains("Error").contains("file_id");
    }

    private long ingest(String conversationId, String content) {
        IngestedFile ingested = fileQaService.ingest("user-1", conversationId, "note.txt", "text/plain",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length());
        return ingested.id();
    }
}
