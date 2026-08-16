package com.agenttrail.conversation.digest;

import com.agenttrail.web.dto.ConversationTurnResponse;
import com.agenttrail.web.service.ConversationHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 按 conversationId 取会话摘要（issue #103）。
 *
 * <p><b>审计</b>：这是一条真实的跨能力数据流——分析会话里的业务数据经摘要进入 PPT 任务、
 * 渲染进幻灯片、存进对象存储。每次消费都留一条日志，写明来源会话和摘要长度。数据本身不打日志
 * （它可能含脱敏后仍然敏感的业务信息），但"谁在什么时候把哪个会话的内容带去了哪里"必须可查。
 */
public class ConversationDigestService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDigestService.class);
    private static final int HISTORY_PAGE_SIZE = 20;

    private final ConversationHistoryService historyService;

    public ConversationDigestService(ConversationHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * @return 空表示这个会话没有可用上下文（新会话、或历史读不出来）——调用方应该原样发起任务，
     *         不要拼一个空摘要上去
     */
    public Optional<String> digestFor(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        try {
            List<ConversationTurnResponse> turns =
                    historyService.findPage(conversationId, 0, HISTORY_PAGE_SIZE).turns();
            return ConversationDigest.render(turns);
        } catch (RuntimeException failure) {
            // 取不到上下文不该让任务本身失败——用户要的是那份 PPT，不是这段摘要
            log.warn("会话 {} 的摘要生成失败，任务将不带上下文继续", conversationId, failure);
            return Optional.empty();
        }
    }

    /**
     * 任务入口用：已有会话就把摘要拼进入参，新会话原样返回。触发条件是**会话状态**，
     * 不去解析用户有没有说"根据前面的"。
     */
    public String withContext(String conversationId, String userMessage, String consumer) {
        Optional<String> digest = digestFor(conversationId);
        if (digest.isEmpty()) {
            return userMessage;
        }
        log.info("跨能力上下文传递：会话 {} 的摘要（{} 字）被 {} 消费",
                conversationId, digest.get().length(), consumer);
        return ConversationDigest.asTaskPrefix(digest.get(), userMessage);
    }
}
