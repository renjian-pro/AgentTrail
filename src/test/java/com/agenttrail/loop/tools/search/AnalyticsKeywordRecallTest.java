package com.agenttrail.loop.tools.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 踩坑点 #85 的回归证据：对着真实从 Golden Task 实测里捕获到的查询词，关键词打分
 * （{@link ToolIndexEntry#score}）对 {@code execute_sql}/{@code lookup_glossary} 全部是 0——
 * HYBRID 检索对这几类真实中文分析查询完全没有从关键词路径拿到任何信号，全部要靠
 * {@code ToolSearchCallback#llmSearch} 兜底，而那才是真正不稳定的一环（见文档）。
 *
 * <p>这个断言故意反直觉：证明的不是"关键词检索工作正常"，是"关键词检索对这套工具描述
 * 基本形同虚设"——如果哪天有人往 {@code execute_sql}/{@code lookup_glossary} 的描述里加了
 * 关键词让这里开始命中，这个测试会失败，提醒回来更新 #85 的结论（说明关键词路径真的补上了）。
 */
class AnalyticsKeywordRecallTest {

    private static final String EXECUTE_SQL_DESCRIPTION =
            "执行只读分析 SQL。服务端自动校验、注入当前用户数据权限、EXPLAIN 预检并对结果脱敏。";
    private static final String LOOKUP_GLOSSARY_DESCRIPTION =
            "按业务术语或同义词精确查询口径；不做模糊匹配。\n"
                    + "当前已登记的术语：租赁次数、付款金额、高价值客户、业绩归属、时间口径、活跃客户、客户留存";

    private final ToolIndexEntry executeSql = entry("execute_sql", EXECUTE_SQL_DESCRIPTION);
    private final ToolIndexEntry lookupGlossary = entry("lookup_glossary", LOOKUP_GLOSSARY_DESCRIPTION);

    @Test
    void keywordScoringFindsNothingForRealAnalyticsQueriesCapturedFromGoldenTaskRuns() {
        assertThat(scoreOf(executeSql, "查询租赁总量")).as("execute_sql 对整句无标点查询").isZero();
        assertThat(scoreOf(lookupGlossary, "查询租赁总量")).isZero();

        assertThat(scoreOf(executeSql, "租赁 查询 状态 金额")).as("execute_sql 对空格分词查询").isZero();
        assertThat(scoreOf(lookupGlossary, "租赁 查询 状态 金额"))
                .as("query token 比 descriptionTokens 里的术语短，List.contains 精确相等不会命中").isZero();

        assertThat(scoreOf(executeSql, "地址查询")).isZero();
        assertThat(scoreOf(lookupGlossary, "地址查询")).isZero();
    }

    private static int scoreOf(ToolIndexEntry entry, String query) {
        List<String> queryTokens = new ArrayList<>(ToolIndexEntry.tokenize(query));
        for (String token : ToolIndexEntry.tokenizeName(query)) {
            if (!queryTokens.contains(token)) queryTokens.add(token);
        }
        return entry.score(query, queryTokens);
    }

    private static ToolIndexEntry entry(String name, String description) {
        return new ToolIndexEntry(name, description, ToolIndexEntry.tokenizeName(name), ToolIndexEntry.tokenize(description));
    }
}
