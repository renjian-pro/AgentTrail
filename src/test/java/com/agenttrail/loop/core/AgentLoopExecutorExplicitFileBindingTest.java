package com.agenttrail.loop.core;

import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.InMemoryFileStore;
import com.agenttrail.capability.file.UploadedFile;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.persistence.TurnRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #110 / R21：文件绑定从「扫一遍这个会话里所有还没归属轮次的」改成「按用户这一轮显式
 * 带上来的 fileIds」。
 *
 * <h2>旧行为同时让四件事成立</h2>
 *
 * <ol>
 *   <li><b>"发送前删除"只是前端幻觉</b> —— 上传接口一返回成功，文件就已经进了下一轮的系统提示词，
 *       跟用户按没按发送、有没有在输入框里把它删掉完全无关
 *   <li><b>多标签页互吞</b> —— 同一会话开两个页签，A 页上传、B 页发送，B 页那轮会把 A 页的文件吃掉
 *   <li><b>跨会话时间的静默附加</b> —— 上传完没发就离开，下次进来随便问一句，上次的文件被悄悄带上
 *   <li><b>无法部分选择</b> —— 传了 3 个只想附 2 个，做不到
 * </ol>
 *
 * 这组用例正反两面都钉住：该可见的可见、不该可见的不可见。
 */
class AgentLoopExecutorExplicitFileBindingTest {

    private static RunnableParams params(Long... fileIds) {
        return new RunnableParams("conv-1", "user-1", Map.of("fileIds", List.of(fileIds)));
    }

    /**
     * <b>第 1、2、3 条缺口的堵法。</b>上传过但这一轮没带上来的文件，模型压根看不到它存在——
     * 系统提示词里没有它的 fileId，{@code load_file_content} 也就无从调用。
     */
    @Test
    @DisplayName("上传了但这一轮没带上来的文件，不进系统提示词")
    void hidesUploadedFilesThatTheUserDidNotAttachToThisTurn() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        fileStore.save(textFile("conv-1", "用户传完又删掉的.txt"));
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore).build();

        executor.stream("随便聊聊", params()).collectList().block(Duration.ofSeconds(5));

        assertThat(fileSectionFrom(chatModel))
                .as("这一轮没带它，模型就不该知道它存在")
                .doesNotContain("用户传完又删掉的.txt");
    }

    /** <b>第 4 条缺口的堵法。</b>三个里只带两个，第三个既不可见也不被绑定。 */
    @Test
    @DisplayName("只带部分文件时，没带的那些既不可见也不被绑定")
    void attachesOnlyTheSubsetTheUserPicked() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        long picked = fileStore.save(textFile("conv-1", "要的.txt"));
        long alsoPicked = fileStore.save(textFile("conv-1", "也要的.txt"));
        long skipped = fileStore.save(textFile("conv-1", "不要的.txt"));
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore)
                .persistenceHook(new FixedIdPersistenceHook(42L))
                .build();

        executor.stream("看这两个", params(picked, alsoPicked)).collectList().block(Duration.ofSeconds(5));

        String section = fileSectionFrom(chatModel);
        assertThat(section).contains("要的.txt").contains("也要的.txt");
        assertThat(section).doesNotContain("不要的.txt");
        assertThat(fileStore.findById(picked).orElseThrow().turnId()).isEqualTo(42L);
        assertThat(fileStore.findById(alsoPicked).orElseThrow().turnId()).isEqualTo(42L);
        assertThat(fileStore.findById(skipped).orElseThrow().turnId())
                .as("没带上来的不该被这一轮吞掉——旧的 sweep 正是这么吞的")
                .isNull();
    }

    /**
     * 收紧可见性不能把"跨轮可见"一起收掉：第 1 轮传的 PDF，第 5 轮追问时模型仍要看得到。
     * 判据是**绑定过没有**，不是"这一轮带没带"。
     */
    @Test
    @DisplayName("已经绑定到某一轮的文件保持跨轮可见，不需要每轮重新带")
    void keepsAlreadyBoundFilesVisibleInLaterTurns() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        long earlier = fileStore.save(textFile("conv-1", "第一轮传的.txt"));
        fileStore.linkFilesToTurn("conv-1", List.of(earlier), 7L);
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore).build();

        executor.stream("再看看那个文件的第三章", params()).collectList().block(Duration.ofSeconds(5));

        assertThat(fileSectionFrom(chatModel)).contains("第一轮传的.txt");
    }

    /**
     * 别人会话的 fileId 传上来也拿不到东西：{@code findByConversationId} 本身就按会话收口，
     * 越权的 id 压根不在结果集里。静默缺席即可，不需要报错——报错反而会泄漏"这个 id 存在"。
     */
    @Test
    @DisplayName("传别人会话的 fileId 拿不到任何内容")
    void ignoresFileIdsThatBelongToAnotherConversation() {
        InMemoryFileStore fileStore = new InMemoryFileStore();
        long theirs = fileStore.save(textFile("conv-other", "别人的机密.txt"));
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .fileStore(fileStore)
                .persistenceHook(new FixedIdPersistenceHook(42L))
                .build();

        executor.stream("给我看这个", params(theirs)).collectList().block(Duration.ofSeconds(5));

        assertThat(fileSectionFrom(chatModel)).doesNotContain("别人的机密.txt");
        assertThat(fileStore.findById(theirs).orElseThrow().turnId())
                .as("跨会话绑定必须失败——生产实现里这道防线在 SQL 的 AND conversation_id = ? 上")
                .isNull();
    }

    private static UploadedFile textFile(String conversationId, String fileName) {
        return new UploadedFile(null, "user-1", conversationId, null, fileName, "text/plain", 12L,
                FileKind.TEXT, "内容", null, System.currentTimeMillis());
    }

    /** 没有任何可见文件时"会话文件"区块整个不会出现——所以取不到就返回空串，不是 orElseThrow。 */
    private static String fileSectionFrom(ScriptedChatModel chatModel) {
        return chatModel.messagesAtRound(0).stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .filter(text -> text.contains("会话文件"))
                .findFirst()
                .orElse("");
    }

    private record FixedIdPersistenceHook(long turnId) implements TurnPersistenceHook {
        @Override
        public Long onTurnComplete(TurnRecord record) {
            return turnId;
        }
    }
}
