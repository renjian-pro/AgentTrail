package com.agenttrail.loop.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #107 / R17：钉住「跨轮历史只回放 question/answer，绝不回放 timeline」这条隐式安全契约。
 *
 * <h2>为什么这条契约值得一个专门的测试</h2>
 *
 * 能力模式是**轮次级**的，用户可以在同一个会话里自由切换（requirements.md §7.2）。切模式＝换执行器
 * ＝工具集在会话内逐轮变化。若历史按原结构回放，第 1 轮数据分析产生的 {@code execute_sql} 调用记录
 * 会在第 2 轮切回普通对话时指向当前执行器根本没挂载的工具，{@code tool_call_id} 悬空。
 *
 * <p>这个故障当前**不会发生**，唯一的原因就是 {@code SELECT_RECENT_TURNS} 只取两列。而
 * {@code agent_session.timeline}（工具调用时间线 JSON）就躺在同一张表里，"让模型看到自己上一轮
 * 查了什么"是个非常自然的优化想法——谁哪天顺手读回来，故障立刻成真，而且**只在切过模式的会话里
 * 复现**，是最难查的那种形状。
 *
 * <h2>这个测试守得住什么、守不住什么（诚实版）</h2>
 *
 * 守得住：任何"把 timeline/think 加进历史查询"的改动。要把工具调用回放进历史，绕不开先改这条 SQL
 * 或给 {@code TurnSummary} 加字段，两条路都会让下面的断言变红。
 *
 * <p>守不住：从别的数据源（比如直接查 {@code agent_trace}）另起一条回放路径。那种改法这里看不见——
 * 真正端到端的行为断言在 {@link JdbcSessionStoreIT}，但它要真实 MySQL，只在 {@code -Pgolden} 下跑，
 * 日常改动碰不到。所以这里用文本断言换"每次 {@code mvn test} 都会跑"，是有意识的取舍。
 */
class HistoryReplayContractTest {

    /** timeline 是工具调用时间线，think 是思考过程——两者都不该出现在喂回模型的历史里。 */
    @Test
    @DisplayName("历史查询只取 question / answer，不碰 timeline")
    void historyQuerySelectsOnlyTheTwoColumnsThatCarryNoToolCallStructure() {
        String sql = JdbcSessionStore.SELECT_RECENT_TURNS.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .as("历史重建必须只读 question/answer——见 JdbcSessionStore.SELECT_RECENT_TURNS 上的说明")
                .contains("select question, answer");
        assertThat(sql)
                .as("timeline 是工具调用时间线。读回它会让跨模式的历史带上指向当前不存在的工具的 "
                        + "tool_call_id（requirements.md §7.3）。要做这件事之前先去读那一节")
                .doesNotContain("timeline");
        assertThat(sql)
                .as("think 是模型的思考过程，同样不进历史——它既不是用户说的话也不是最终答案")
                .doesNotContain("think");
    }

    /**
     * 第二道闸：光改 SQL 还不够，回放路径要多带一个字段就得先给 {@code TurnSummary} 加分量。
     * 两处都钉住，是因为有人可能先加字段、以为没生效就顺手再改 SQL。
     */
    @Test
    @DisplayName("TurnSummary 只有 question / answer 两个分量")
    void turnSummaryCarriesNothingBeyondTheQuestionAndTheAnswer() throws Exception {
        Class<?> turnSummary = Class.forName("com.agenttrail.loop.persistence.JdbcSessionStore$TurnSummary");

        assertThat(turnSummary.isRecord()).isTrue();
        assertThat(Arrays.stream(turnSummary.getRecordComponents()).map(RecordComponent::getName))
                .as("给它加字段是「把工具调用回放进历史」的第一步——先读 requirements.md §7.3")
                .containsExactly("question", "answer");
    }
}
