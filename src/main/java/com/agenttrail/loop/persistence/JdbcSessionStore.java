package com.agenttrail.loop.persistence;

import com.agenttrail.loop.context.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记录的 JDBC 落地实现，同时承担两件事：
 * <ul>
 *   <li>{@link TurnPersistenceHook}：一轮结束时把这轮问答写进 {@code agent_session}
 *   <li>{@link #loadHistory}：下一轮开始前按 token 预算把历史读回来
 * </ul>
 *
 * <p>表结构见 {@code db/schema.sql}，那里解释了为什么需要 conversation_id 和 id 两个独立的 key。
 */
public class JdbcSessionStore implements TurnPersistenceHook {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionStore.class);

    private static final String INSERT_TURN = """
            INSERT INTO agent_session
                (conversation_id, user_id, question, answer, think, timeline,
                 first_response_time, total_response_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 按时间倒序取最近若干轮，交给 {@link HistoryBudget} 决定实际带回多少。
     *
     * <p>这里的 limit 只是一道粗筛，防止会话很长时把整张表都读进内存；真正的裁剪按 token 预算做，
     * 因为条数和体积根本不成比例——十轮闲聊和十轮各拖着上千行查询结果完全是两码事。
     *
     * <p><b>只取 question / answer 两列是一条安全契约，不是偷懒（issue #107 / R17）。</b>
     * {@code agent_session.timeline} 存着这一轮的工具调用时间线 JSON，就在同一张表里，
     * 读回来看着像是"让模型看到自己上一轮查了什么"的顺手优化——**不要这么做**，理由如下：
     *
     * <p>能力模式是**轮次级**的，用户可以在同一个会话里自由切换（requirements.md §7.2）。
     * 切模式＝换执行器＝工具集在会话内逐轮变化。如果历史按原结构回放，第 1 轮数据分析产生的
     * {@code execute_sql} 调用记录，会在第 2 轮切回普通对话时指向一个当前执行器根本没挂载的工具——
     * {@code tool_call_id} 悬空，模型收到断裂的调用/结果配对。
     *
     * <p>当前实现天然不会发生，正是因为这两列：跨轮历史重建出的只有 UserMessage / AssistantMessage
     * 对，{@code tool_call} 和 tool result 消息只活在单轮 ReAct 循环内部（暂停恢复用的
     * {@code PauseState} 同样只在轮内）。**工具集的稳定作用域因此天然就是"一轮"**——这也正是
     * "模式不需要会话级绑定、用户不必为换模式新开会话"的直接依据（§7.3 有完整推导，
     * 包括那条曾经据此做过会话级绑定、后来核对实现才发现约束不存在的记录）。
     *
     * <p>要改这里，先读 §7.3；改完 {@code HistoryReplayContractTest} 会变红。
     *
     * <p>包级可见（而不是 private）是**故意**的：{@code HistoryReplayContractTest} 直接读这个常量
     * 来守契约。这是本仓库唯一能在 {@code mvn test} 里跑的守法——{@code JdbcSessionStoreIT} 需要
     * 真实 MySQL，只在 {@code -Pgolden} 下才由 failsafe 执行，日常改动碰不到它。
     */
    static final String SELECT_RECENT_TURNS = """
            SELECT question, answer
            FROM agent_session
            WHERE conversation_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;

    /** 粗筛上限：先按条数限一道，再按 token 预算精裁。 */
    private static final int MAX_TURNS_TO_SCAN = 50;

    private final JdbcClient jdbcClient;

    public JdbcSessionStore(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    /**
     * 写入一轮问答，返回自增主键。
     *
     * <p>返回的 id 会被塞进 Complete 事件——前端要用它把"这一轮上传的附件"关联到正确的轮次上。
     */
    @Override
    public Long onTurnComplete(TurnRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql(INSERT_TURN)
                .param(record.conversationId())
                .param(record.userId())
                .param(record.question())
                .param(record.answer())
                .param(record.think())
                .param(record.timeline())
                .param(record.firstResponseTime())
                .param(record.totalResponseTime())
                .update(keyHolder);

        Number key = keyHolder.getKey();
        Long turnId = (key == null) ? null : key.longValue();
        log.debug("已落库会话 {} 的一轮问答，id={}", record.conversationId(), turnId);
        return turnId;
    }

    /**
     * 读回一个会话的历史，重建成可以直接喂给模型的消息列表。
     *
     * <p><b>产出的消息列表里只有 {@link UserMessage} 和 {@link AssistantMessage}，
     * 永远不含 {@code tool_calls} 结构</b>——这是一条安全契约，理由见
     * {@link #SELECT_RECENT_TURNS} 上的说明（issue #107 / R17）。
     *
     * @param conversationId 会话标识
     * @param tokenBudget    允许历史占用的 token 上限，超出部分从最老的开始丢
     * @return 按时间正序的历史消息，仅含用户/助手两种角色
     */
    @Override
    public List<Message> loadHistory(String conversationId, int tokenBudget) {
        List<TurnSummary> recentFirst = jdbcClient.sql(SELECT_RECENT_TURNS)
                .param(conversationId)
                .param(MAX_TURNS_TO_SCAN)
                .query((rs, rowNum) -> new TurnSummary(rs.getString("question"), rs.getString("answer")))
                .list();

        // 查询是倒序（要取"最近"的），拼消息时要翻回正序
        List<Message> chronological = new ArrayList<>();
        for (int i = recentFirst.size() - 1; i >= 0; i--) {
            TurnSummary turn = recentFirst.get(i);
            chronological.add(new UserMessage(turn.question()));
            if (turn.answer() != null) {
                chronological.add(new AssistantMessage(turn.answer()));
            }
        }

        List<Message> withinBudget = HistoryBudget.fitWithin(chronological, tokenBudget);
        log.debug("会话 {} 读回 {} 条历史（原始 {} 条，预算 {} tokens，实际约 {} tokens）",
                conversationId, withinBudget.size(), chronological.size(), tokenBudget,
                TokenEstimator.estimateTokens(withinBudget));
        return withinBudget;
    }

    /**
     * 历史重建只需要问答两列。
     *
     * <p>**不要给这个 record 加 timeline 字段**——那是 {@link #SELECT_RECENT_TURNS} 那条契约的
     * 第一步。省下大字段的 IO 只是附带好处，真正的理由是跨模式的 {@code tool_call_id} 悬空。
     */
    private record TurnSummary(String question, String answer) {
    }
}
