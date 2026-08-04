package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.file.FileKind;
import com.agenttrail.loop.file.InMemoryFileStore;
import com.agenttrail.loop.file.UploadedFile;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.persistence.TurnRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #28：同一轮多文件在 system prompt 里按"本轮上传"成组渲染，不跟历史文件混在一起；
 * 轮次结束后把这一轮上传但还没归属的文件回填到刚落库的 turnId；没配置 {@code FileStore} 时
 * 什么都不发生。复用 issue #19（{@link AgentLoopExecutorMemoryTest}）已验证过的注入缝——
 * 直接断言 {@code chatModel.messagesAtRound(0)}。
 */
class AgentLoopExecutorFileTest {

    private static final RunnableParams PARAMS = new RunnableParams("conv-1", "user-1");

    @Test
    void groupsFilesUploadedInTheCurrentRoundSeparatelyFromEarlierRounds() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        fileStore.save(textFile("conv-1", "old.txt"));
        long linkedId = fileStore.save(textFile("conv-1", "old.txt"));
        fileStore.linkFilesToTurn("conv-1", 999L); // 模拟这两份文件已经归属到更早的一轮
        fileStore.save(textFile("conv-1", "new-a.txt"));
        fileStore.save(textFile("conv-1", "new-b.txt"));

        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore)
                .build();

        executor.stream("看看这两个文件", PARAMS).collectList().block(Duration.ofSeconds(5));

        String fileSection = fileSectionFrom(chatModel);
        assertThat(fileSection).contains("本轮上传的文件").contains("此前上传的文件");
        int currentHeadingIndex = fileSection.indexOf("本轮上传的文件");
        int priorHeadingIndex = fileSection.indexOf("此前上传的文件");
        String currentGroup = fileSection.substring(currentHeadingIndex, priorHeadingIndex);
        String priorGroup = fileSection.substring(priorHeadingIndex);

        assertThat(currentGroup).contains("new-a.txt").contains("new-b.txt").doesNotContain("old.txt");
        assertThat(priorGroup).contains("old.txt").doesNotContain("new-a.txt").doesNotContain("new-b.txt");
        assertThat(linkedId).isPositive();
    }

    @Test
    void linksFilesUploadedThisRoundToTheNewlyPersistedTurnIdAfterCompletion() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        long fileId = fileStore.save(textFile("conv-1", "note.txt"));
        FixedIdPersistenceHook persistenceHook = new FixedIdPersistenceHook(42L);

        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore)
                .persistenceHook(persistenceHook)
                .build();

        executor.stream("看看这个文件", PARAMS).collectList().block(Duration.ofSeconds(5));

        assertThat(fileStore.findById(fileId).orElseThrow().turnId()).isEqualTo(42L);
    }

    @Test
    void doesNotRelinkFilesThatAlreadyBelongToAnEarlierTurn() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        long fileId = fileStore.save(textFile("conv-1", "old.txt"));
        fileStore.linkFilesToTurn("conv-1", 7L);
        FixedIdPersistenceHook persistenceHook = new FixedIdPersistenceHook(42L);

        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore)
                .persistenceHook(persistenceHook)
                .build();

        executor.stream("继续聊", PARAMS).collectList().block(Duration.ofSeconds(5));

        assertThat(fileStore.findById(fileId).orElseThrow().turnId())
                .as("已经归属到第 7 轮的文件不该被这一轮（第 42 轮）覆盖")
                .isEqualTo(7L);
    }

    /**
     * 回归测试：曾经这里是"没有文件就什么都不注入"，但沉默会被模型当成不确定而不是确实
     * 没有，实测会让模型为了回答数据/图表类问题去猜一个不存在的 fileId 调用
     * load_file_content。FileStore 配置了但这个会话没有文件时，必须显式声明这一点。
     */
    @Test
    void statesExplicitlyThatThereAreNoFilesWhenFileStoreIsConfiguredButEmptyForTheConversation() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore)
                .build();

        executor.stream("你好", PARAMS).collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.messagesAtRound(0).get(0)).isInstanceOf(SystemMessage.class);
        assertThat(chatModel.messagesAtRound(0).get(0).getText())
                .contains("没有已上传的文件")
                .contains("不要调用 load_file_content");
    }

    @Test
    void doesNothingWhenNoFileStoreIsConfigured() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("你好")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        executor.stream("你好", PARAMS).collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.messagesAtRound(0).get(0)).isNotInstanceOf(SystemMessage.class);
    }

    private static UploadedFile textFile(String conversationId, String fileName) {
        return new UploadedFile(null, conversationId, null, fileName, "text/plain", 10, FileKind.TEXT, "content",
                null, 1L);
    }

    private static String fileSectionFrom(ScriptedChatModel chatModel) {
        return chatModel.messagesAtRound(0).stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .filter(text -> text.contains("会话文件"))
                .findFirst()
                .orElseThrow();
    }

    /** 固定返回同一个 turnId 的持久化回调——只关心回填行为，不需要真的记住问答内容。 */
    private record FixedIdPersistenceHook(long turnId) implements TurnPersistenceHook {
        @Override
        public Long onTurnComplete(TurnRecord record) {
            return turnId;
        }
    }
}
