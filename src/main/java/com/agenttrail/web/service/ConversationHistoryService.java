package com.agenttrail.web.service;
import com.agenttrail.web.dto.ConversationSummaryResponse;
import com.agenttrail.web.dto.ConversationTurnResponse;
import com.agenttrail.web.dto.ConversationHistoryResponse;
import com.agenttrail.web.dto.ConversationPageResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;

/** 会话回放查询独立于运行时的历史加载：前者面向 UI 全量分页，后者受 token 预算约束。 */
public class ConversationHistoryService {

    private static final String SELECT_TURNS = """
            SELECT id, question, answer, think, timeline, created_at
            FROM agent_session
            WHERE conversation_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;
    private static final String SELECT_TURNS_FOR_USER = """
            SELECT id, question, answer, think, timeline, created_at FROM agent_session
            WHERE conversation_id = ? AND user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
            """;

    private static final String SELECT_CONVERSATIONS = """
            SELECT latest.id, latest.conversation_id, first_turn.question, latest.created_at
            FROM (
                SELECT conversation_id, MIN(id) AS first_id, MAX(id) AS latest_id
                FROM agent_session
                GROUP BY conversation_id
            ) grouped
            JOIN agent_session first_turn ON first_turn.id = grouped.first_id
            JOIN agent_session latest ON latest.id = grouped.latest_id
            ORDER BY latest.created_at DESC, latest.id DESC
            LIMIT ? OFFSET ?
            """;
    private static final String SELECT_CONVERSATIONS_FOR_USER = """
            SELECT latest.id, latest.conversation_id, first_turn.question, latest.created_at
            FROM (
                SELECT conversation_id, MIN(id) AS first_id, MAX(id) AS latest_id
                FROM agent_session WHERE user_id = ? GROUP BY conversation_id
            ) grouped
            JOIN agent_session first_turn ON first_turn.id = grouped.first_id
            JOIN agent_session latest ON latest.id = grouped.latest_id
            ORDER BY latest.created_at DESC, latest.id DESC LIMIT ? OFFSET ?
            """;

    private final JdbcClient jdbcClient;

    public ConversationHistoryService(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    public ConversationHistoryResponse findPage(String conversationId, int page, int size) {
        return findPageInternal(null, conversationId, page, size);
    }

    public ConversationHistoryResponse findPage(String userId, String conversationId, int page, int size) {
        return findPageInternal(userId, conversationId, page, size);
    }

    private ConversationHistoryResponse findPageInternal(String userId, String conversationId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        var statement = jdbcClient.sql(userId == null ? SELECT_TURNS : SELECT_TURNS_FOR_USER)
                .param(conversationId);
        if (userId != null) statement = statement.param(userId);
        List<ConversationTurnResponse> newestFirst = statement.param(safeSize + 1).param(safePage * safeSize)
                .query((rs, rowNum) -> new ConversationTurnResponse(
                        rs.getLong("id"), rs.getString("question"), rs.getString("answer"),
                        rs.getString("think"), rs.getString("timeline"),
                        rs.getTimestamp("created_at").getTime()))
                .list();
        boolean hasMore = newestFirst.size() > safeSize;
        if (hasMore) {
            newestFirst = newestFirst.subList(0, safeSize);
        }
        Collections.reverse(newestFirst);
        return new ConversationHistoryResponse(conversationId, safePage, safeSize, hasMore, newestFirst);
    }

    /**
     * 标题固定使用首轮提问，排序使用最新轮次时间——对齐 dodo-agentx 的会话语义：会话继续进行时
     * 标题不能每问一句就变化；列表仍要让最近活跃的会话排在最上面。
     */
    public ConversationPageResponse findConversations(int page, int size) {
        return findConversationsInternal(null, page, size);
    }

    public ConversationPageResponse findConversations(String userId, int page, int size) {
        return findConversationsInternal(userId, page, size);
    }

    private ConversationPageResponse findConversationsInternal(String userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        var statement = jdbcClient.sql(userId == null ? SELECT_CONVERSATIONS : SELECT_CONVERSATIONS_FOR_USER);
        if (userId != null) statement = statement.param(userId);
        List<ConversationSummaryResponse> newestFirst = statement.param(safeSize + 1)
                .param(safePage * safeSize)
                .query((rs, rowNum) -> new ConversationSummaryResponse(
                        rs.getString("conversation_id"), rs.getString("question"),
                        rs.getTimestamp("created_at").getTime()))
                .list();
        boolean hasMore = newestFirst.size() > safeSize;
        if (hasMore) {
            newestFirst = newestFirst.subList(0, safeSize);
        }
        return new ConversationPageResponse(safePage, safeSize, hasMore, newestFirst);
    }

    public boolean belongsToUser(String conversationId, String userId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM agent_session WHERE conversation_id = ? AND user_id = ?")
                .param(conversationId).param(userId).query(Long.class).single() > 0;
    }
}
