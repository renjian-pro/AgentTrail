package com.agenttrail.loop.persistence;

import com.agenttrail.capability.file.FileStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 生产装配用的 {@link TurnCommitter}：轮次落库 + 附件绑定跑在同一个事务里（issue #110 / R21）。
 *
 * <p>绑定失败时整轮回滚——包括那条 {@code agent_session} 记录。这看起来"损失"了一轮问答，
 * 但比另一种结果好：留下一条轮次记录、而它的附件永远指不回来，且没有任何路径会再修它。
 * 用户重发一次就能恢复，而中间态是不可恢复的。
 */
public final class TransactionalTurnCommitter implements TurnCommitter {

    private final TurnPersistenceHook persistenceHook;
    private final FileStore fileStore;
    private final TransactionTemplate transactionTemplate;

    public TransactionalTurnCommitter(TurnPersistenceHook persistenceHook, FileStore fileStore,
                                      PlatformTransactionManager transactionManager) {
        this.persistenceHook = java.util.Objects.requireNonNull(persistenceHook);
        // 非空由装配保证（AgentLoopExecutorConfig 在 fileStore 为 null 时压根不构造这个类）——
        // 没有附件要绑就只剩一条 INSERT，单步操作本来就是原子的，不需要事务
        this.fileStore = java.util.Objects.requireNonNull(fileStore);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Long commit(TurnRecord record, List<Long> fileIds) {
        // 绝大多数轮次不带附件，那就只有一条 INSERT——本来就是原子的，没必要为它借连接、
        // 走一遍 SET autocommit=0 / COMMIT
        if (fileIds == null || fileIds.isEmpty()) {
            return persistenceHook.onTurnComplete(record);
        }
        return transactionTemplate.execute(status -> {
            Long turnId = persistenceHook.onTurnComplete(record);
            if (turnId != null) {
                fileStore.linkFilesToTurn(record.conversationId(), fileIds, turnId);
            }
            return turnId;
        });
    }
}
