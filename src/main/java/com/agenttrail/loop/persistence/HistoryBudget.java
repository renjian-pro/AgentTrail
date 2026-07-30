package com.agenttrail.loop.persistence;

import com.agenttrail.loop.context.TokenEstimator;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 token 预算裁剪历史消息，决定重建对话时到底带回多少轮。
 *
 * <p>不用"保留最近 N 轮"这种固定条数：同样是 10 轮，每轮一句话时白白浪费了可用上下文，
 * 每轮都拖着几千行查询结果时又足以撑爆窗口。条数和体积根本不成比例，只能按预算倒推。
 *
 * <p>与 {@link com.agenttrail.loop.context.ContextCompactor} 的分工：本类管的是
 * **一轮开始前**从数据库带回多少历史，压缩器管的是**一轮进行中**上下文膨胀了怎么办。
 */
public final class HistoryBudget {

    private HistoryBudget() {
    }

    /**
     * 从最新的一条往回装，装不下就停。
     *
     * @param history     完整历史，按时间正序
     * @param tokenBudget 允许历史占用的 token 上限
     * @return 预算内的历史，仍按时间正序
     */
    public static List<Message> fitWithin(List<Message> history, int tokenBudget) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<Message> kept = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            List<Message> candidate = new ArrayList<>(kept);
            candidate.add(0, history.get(i));

            // 最近一条无论多大都要留下：让模型完全看不到上一轮说了什么，比稍微超预算更糟
            if (!kept.isEmpty() && TokenEstimator.estimateTokens(candidate) > tokenBudget) {
                break;
            }
            kept = candidate;
        }
        return List.copyOf(kept);
    }
}
