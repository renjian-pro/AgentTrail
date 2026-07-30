package com.agenttrail.loop.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史重建要保留多少轮，不能拍一个固定条数。
 *
 * <p>"最近 10 轮"这种写法在两个方向上都错：每轮只有一句话时白白丢掉了可用的上下文；
 * 每轮都带着几千行 SQL 查询结果时，10 轮足以撑爆窗口。正确做法是按 token 预算倒推——
 * 从最新的往回装，装不下就停，最老的先被丢掉。
 */
class HistoryBudgetTest {

    @Test
    void keepsEverythingWhenTheBudgetIsGenerous() {
        List<Message> history = List.of(
                new UserMessage("第一问"), new AssistantMessage("第一答"),
                new UserMessage("第二问"), new AssistantMessage("第二答"));

        assertThat(HistoryBudget.fitWithin(history, 100_000)).containsExactlyElementsOf(history);
    }

    /** 超预算时从**最老**的开始丢——最近的对话对当前问题最相关。 */
    @Test
    void dropsTheOldestTurnsFirstWhenOverBudget() {
        List<Message> history = List.of(
                new UserMessage("很久以前的问题".repeat(200)),
                new AssistantMessage("很久以前的回答".repeat(200)),
                new UserMessage("刚刚的问题"),
                new AssistantMessage("刚刚的回答"));

        List<Message> kept = HistoryBudget.fitWithin(history, 100);

        assertThat(kept).isNotEmpty();
        assertThat(kept.get(kept.size() - 1).getText()).isEqualTo("刚刚的回答");
        assertThat(kept).noneSatisfy(message ->
                assertThat(message.getText()).contains("很久以前"));
    }

    /** 单条消息就超预算时也不能返回空——宁可超一点，也好过让模型完全看不到上一轮说了什么。 */
    @Test
    void alwaysKeepsAtLeastTheMostRecentMessage() {
        List<Message> history = List.of(new AssistantMessage("超长回答".repeat(5000)));

        assertThat(HistoryBudget.fitWithin(history, 10)).hasSize(1);
    }

    @Test
    void handlesAnEmptyHistory() {
        assertThat(HistoryBudget.fitWithin(List.of(), 1000)).isEmpty();
        assertThat(HistoryBudget.fitWithin(null, 1000)).isEmpty();
    }

    /** 保留的必须是原始顺序，不能因为"从后往前装"就把顺序也倒过来。 */
    @Test
    void preservesChronologicalOrder() {
        List<Message> history = List.of(
                new UserMessage("一"), new AssistantMessage("二"),
                new UserMessage("三"), new AssistantMessage("四"));

        assertThat(HistoryBudget.fitWithin(history, 100_000))
                .extracting(Message::getText)
                .containsExactly("一", "二", "三", "四");
    }
}
