package com.agenttrail.loop.security;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #104。真实故障：模型在没有数据库工具的会话里被问业务数据，编了一份演示数据画成图表，
 * 画完才补一句"这是模拟数据"。产出看起来完全正常——这是最危险的一类失败。
 */
class DataProvenancePolicyTest {

    private static final DataProvenancePolicy POLICY =
            new DataProvenancePolicy(Set.of("generate_bar_chart"), Set.of("execute_sql", "load_file_content"));

    private static Message toolResult(String toolName, String result) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", toolName, result)))
                .build();
    }

    @Test
    void guardsOnlyTheConfiguredConsumers() {
        assertThat(POLICY.guards("generate_bar_chart")).isTrue();
        assertThat(POLICY.guards("execute_sql")).isFalse();
    }

    /** 两个名单任一为空都不启用——不做半吊子拦截。 */
    @Test
    void staysOutOfTheWayWhenEitherListIsEmpty() {
        assertThat(DataProvenancePolicy.DISABLED.guards("generate_bar_chart")).isFalse();
        assertThat(new DataProvenancePolicy(Set.of("generate_bar_chart"), Set.of()).guards("generate_bar_chart"))
                .isFalse();
    }

    @Test
    void acceptsASuccessfulProducerAsTheDataSource() {
        assertThat(POLICY.satisfiedBy(List.of(toolResult("execute_sql", "| store | revenue |\n| A | 100 |"))))
                .isTrue();
    }

    /** 失败的工具结果里没有数据，不能当来源——否则"查询失败了但我还是画个图"照样能过。 */
    @Test
    void refusesToTreatAFailedProducerAsASource() {
        assertThat(POLICY.satisfiedBy(List.of(toolResult("execute_sql", "Error: 查询超时")))).isFalse();
        assertThat(POLICY.satisfiedBy(List.of(toolResult("execute_sql", "")))).isFalse();
    }

    /** 别的工具跑过不算——联网搜索的文本不是这张图的数据来源。 */
    @Test
    void ignoresToolsThatAreNotOnTheProducerList() {
        assertThat(POLICY.satisfiedBy(List.of(toolResult("tavily_search", "一些新闻正文")))).isFalse();
    }

    /**
     * 用户自己贴数据必须认，否则"我把 Excel 导出的数据粘给你，帮我画个图"这个完全合法的用法
     * 会被一起堵死——堵掉伪造不能以牺牲真实用法为代价。
     */
    @Test
    void acceptsDataThatTheUserPastedIn() {
        assertThat(POLICY.satisfiedBy(List.of(new UserMessage("| 门店 | 营收 |\n| A | 100 |")))).isTrue();
        assertThat(POLICY.satisfiedBy(List.of(new UserMessage("1月 120 2月 135 3月 150 4月 160")))).isTrue();
    }

    @Test
    void doesNotMistakeAnOrdinaryQuestionForPastedData() {
        assertThat(POLICY.satisfiedBy(List.of(new UserMessage("各门店的营收排名是怎样的？")))).isFalse();
        assertThat(POLICY.satisfiedBy(List.of())).isFalse();
    }

    /** 拒绝理由要具体到"下一步该做什么"，模型才能自洽改写而不是原地重试。 */
    @Test
    void tellsTheModelWhatToDoInstead() {
        assertThat(POLICY.rejectionReason("generate_bar_chart"))
                .startsWith("Error:")
                .contains("generate_bar_chart")
                .contains("execute_sql")
                .contains("不要用自己推断的数字作图");
    }
}
