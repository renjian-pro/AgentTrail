package com.agenttrail.loop.persistence;

import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.FileStore;
import com.agenttrail.capability.file.JdbcFileStore;
import com.agenttrail.capability.file.UploadedFile;
import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * issue #110 / R21：轮次落库 + 附件绑定的**原子性**，跑真实 MySQL。
 *
 * <h2>为什么这条必须用真库</h2>
 *
 * 回滚是数据库行为，内存实现里模拟不出来。项目又明令禁用 H2（SQL 语义和 MySQL 不一致，会让本该
 * 暴露的问题静静躺过测试），所以只能连开发机上常驻的那个实例，和 {@code JdbcSessionStoreIT} 同一条规矩。
 *
 * <h2>为什么原子性是必需的，不是"顺手加的"</h2>
 *
 * 改成按 {@code fileIds} 精确绑之前，绑定是"扫一遍会话里所有 {@code turn_id IS NULL} 的行"。那个
 * 扫法有个副作用是**自愈**——这一轮绑失败，下一轮会顺手扫走。精确绑之后这个兜底没了：轮次落了库、
 * 绑定失败，就留下"轮次存在、文件永远 {@code turn_id IS NULL}"的中间态，**没有任何后续路径会再修它**，
 * 那个文件从此在历史回放里消失，虽然行还好端端躺在表里。
 */
class TransactionalTurnCommitterIT {

    private static DataSource dataSource;
    private JdbcClient jdbc;
    private FileStore fileStore;
    private JdbcSessionStore sessionStore;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                SharedMySql.jdbcUrl(), SharedMySql.username(), SharedMySql.password());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        dataSource = source;
    }

    @BeforeEach
    void resetTables() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE agent_file").update();
        jdbc.sql("TRUNCATE TABLE agent_session").update();
        fileStore = new JdbcFileStore(dataSource);
        sessionStore = new JdbcSessionStore(dataSource);
    }

    private TurnCommitter committer(FileStore store) {
        return new TransactionalTurnCommitter(sessionStore, store, new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("正常路径：轮次落库，附件绑到这一轮")
    void commitsTheTurnAndBindsTheAttachmentsTogether() {
        long fileId = fileStore.save(file("conv-1", "note.txt"));

        Long turnId = committer(fileStore).commit(turn("conv-1", "看看这个"), List.of(fileId));

        assertThat(turnId).isNotNull();
        assertThat(fileStore.findById(fileId).orElseThrow().turnId()).isEqualTo(turnId);
        assertThat(countTurns("conv-1")).isEqualTo(1);
    }

    /**
     * <b>本票唯一无法在单测里验证的东西，也是引入 {@link TurnCommitter} 这个接缝的全部理由。</b>
     *
     * <p>绑定这一步炸掉时，那条 {@code agent_session} 记录必须一起回滚。看起来"损失"了一轮问答，
     * 但比另一种结果好得多：留下一条轮次记录、而它的附件永远指不回来，且不可自愈。
     * 用户重发一次就恢复了，中间态恢复不了。
     */
    @Test
    @DisplayName("绑定失败时整轮回滚，不留「轮次有、文件永远 NULL」的中间态")
    void rollsBackThePersistedTurnWhenBindingBlowsUp() {
        long fileId = fileStore.save(file("conv-1", "note.txt"));
        FileStore explodingOnLink = new DelegatingFileStore(fileStore) {
            @Override
            public void linkFilesToTurn(String conversationId, List<Long> fileIds, long turnId) {
                throw new IllegalStateException("模拟绑定阶段炸掉");
            }
        };

        assertThatThrownBy(() -> committer(explodingOnLink).commit(turn("conv-1", "看看这个"), List.of(fileId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countTurns("conv-1"))
                .as("绑定失败了，这一轮就不该留在 agent_session 里——否则那个文件永远指不回来")
                .isZero();
        assertThat(fileStore.findById(fileId).orElseThrow().turnId()).isNull();
    }

    @Test
    @DisplayName("这一轮没带文件时照常落库，不因为空列表跳过")
    void stillPersistsTheTurnWhenNoFilesWereAttached() {
        Long turnId = committer(fileStore).commit(turn("conv-1", "随便聊聊"), List.of());

        assertThat(turnId).isNotNull();
        assertThat(countTurns("conv-1")).isEqualTo(1);
    }

    /** 跨会话绑定在 SQL 的 {@code AND conversation_id = ?} 上就被挡掉，不依赖任何上层校验。 */
    @Test
    @DisplayName("别的会话的 fileId 传上来也绑不上")
    void refusesToBindFileIdsFromAnotherConversation() {
        long theirs = fileStore.save(file("conv-other", "别人的.txt"));

        Long turnId = committer(fileStore).commit(turn("conv-1", "给我看这个"), List.of(theirs));

        assertThat(turnId).isNotNull();
        assertThat(fileStore.findById(theirs).orElseThrow().turnId()).isNull();
    }

    /** {@code turn_id IS NULL} 挡住"把已经属于第 7 轮的文件改绑到这一轮"。 */
    @Test
    @DisplayName("已经归属过的文件不会被改绑到新的一轮")
    void neverRebindsAFileThatAlreadyBelongsToAnEarlierTurn() {
        long fileId = fileStore.save(file("conv-1", "old.txt"));
        fileStore.linkFilesToTurn("conv-1", List.of(fileId), 7L);

        committer(fileStore).commit(turn("conv-1", "继续聊"), List.of(fileId));

        assertThat(fileStore.findById(fileId).orElseThrow().turnId()).isEqualTo(7L);
    }

    private int countTurns(String conversationId) {
        return jdbc.sql("SELECT COUNT(*) FROM agent_session WHERE conversation_id = ?")
                .param(conversationId).query(Integer.class).single();
    }

    private static TurnRecord turn(String conversationId, String question) {
        return new TurnRecord(conversationId, "u-1", question, "答案", null, null, 100L, 1_000L);
    }

    private static UploadedFile file(String conversationId, String fileName) {
        return new UploadedFile(null, "u-1", conversationId, null, fileName, "text/plain", 12L,
                FileKind.TEXT, "内容", null, System.currentTimeMillis());
    }

    /** 只想改写一个方法，其余原样委托——避免为一个用例手写整套 FileStore。 */
    private static class DelegatingFileStore implements FileStore {
        private final FileStore delegate;

        DelegatingFileStore(FileStore delegate) {
            this.delegate = delegate;
        }

        @Override public long save(UploadedFile file) { return delegate.save(file); }
        @Override public java.util.Optional<UploadedFile> findById(long id) { return delegate.findById(id); }
        @Override public List<UploadedFile> findByConversationId(String id) { return delegate.findByConversationId(id); }
        @Override public List<UploadedFile> findVisibleForPrompt(String id, List<Long> fileIds) {
            return delegate.findVisibleForPrompt(id, fileIds);
        }
        @Override public void updateParsedText(long id, String parsedText) { delegate.updateParsedText(id, parsedText); }
        @Override public void linkFilesToTurn(String conversationId, List<Long> fileIds, long turnId) {
            delegate.linkFilesToTurn(conversationId, fileIds, turnId);
        }
        @Override public void delete(long id) { delegate.delete(id); }
    }
}
