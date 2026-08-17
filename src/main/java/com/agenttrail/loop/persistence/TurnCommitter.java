package com.agenttrail.loop.persistence;

import java.util.List;

/**
 * 把「这一轮落库」和「把本轮附件绑到这一轮」合成**一个原子操作**（issue #110 / R21）。
 *
 * <h2>为什么需要这个接缝</h2>
 *
 * 改成显式 {@code fileIds} 之前，绑定是"扫一遍这个会话里所有 {@code turn_id IS NULL} 的行"。
 * 那个扫法有个没人注意到的副作用——**自愈**：这一轮绑失败了，下一轮会顺手把它们扫走。
 *
 * <p>改成按 id 精确绑之后，这个兜底消失了。如果轮次已经落库、绑定却失败，会留下
 * "轮次存在、文件永远 {@code turn_id IS NULL}"的中间态，而且**没有任何后续路径会再修它**——
 * 那个文件从此在历史回放里消失，虽然行还好端端躺在表里。
 *
 * <p>所以这两步必须同生共死。这是从隐式改显式必须一起付的代价，不是可选优化。
 *
 * <h2>为什么是接口而不是直接写死事务</h2>
 *
 * {@code AgentLoopExecutor} 不是 Spring 管理的 bean，加不了 {@code @Transactional}；而它同时
 * 服务于一堆没有数据库的装配（单测、内部编排子调用）。接缝让"要不要事务"成为装配期的选择：
 * 默认实现就是原来那两行，生产装配注入 {@link TransactionalTurnCommitter}。
 */
public interface TurnCommitter {

    /**
     * @param fileIds 本轮显式带上来的文件；空列表表示没带，实现不应因此跳过轮次落库
     * @return 落库后的单轮记录 id；实现方无法提供时返回 null（此时 Complete 事件不带 id）
     */
    Long commit(TurnRecord record, List<Long> fileIds);

    /**
     * 非事务的默认实现——就是引入这个接缝之前 {@code AgentLoopExecutor} 里的那两行。
     *
     * <p>有了它，执行器永远拿得到一个 committer，不需要"有就用、没有就走另一条分支"的双路径：
     * 两条分支必须永远保持行为一致，而只有生产那条被集成测试覆盖，任何提交顺序上的改动
     * （比如 spec §1.8 的附件状态）都得改两遍。**要不要事务变成纯装配期的选择。**
     */
    static TurnCommitter direct(TurnPersistenceHook persistenceHook,
                                com.agenttrail.capability.file.FileStore fileStore) {
        return (record, fileIds) -> {
            Long turnId = persistenceHook == null ? null : persistenceHook.onTurnComplete(record);
            if (fileStore != null && turnId != null) {
                fileStore.linkFilesToTurn(record.conversationId(), fileIds, turnId);
            }
            return turnId;
        };
    }
}
