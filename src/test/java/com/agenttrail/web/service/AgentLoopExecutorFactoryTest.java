package com.agenttrail.web.service;

import com.agenttrail.capability.analytics.AnalyticsToolProvider;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.skills.SkillManager;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.capability.file.FileQaService;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.tools.FileContentTool;
import com.agenttrail.loop.tools.chart.ChartToolCallback;
import com.agenttrail.loop.tools.chart.ChartToolProvider;
import com.agenttrail.loop.tools.websearch.TavilySearchToolProvider;
import com.agenttrail.loop.tools.websearch.TavilyWebSearchResultParser;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLoopExecutorFactoryTest {

    private static List<RegisteredModel> twoModels(ScriptedChatModel deepSeek, ScriptedChatModel qwen) {
        return List.of(
                new RegisteredModel("deepseek-chat", deepSeek, ThinkingMode.REASONING_CONTENT),
                new RegisteredModel("qwen-plus", qwen, ThinkingMode.DISABLED));
    }

    /** 空 key 时 {@code toolCallbacks()} 不发网络请求，直接返回空列表——不需要真的连 Tavily 就能测降级路径。 */
    private static TavilySearchToolProvider degradedSearchProvider() {
        return new TavilySearchToolProvider("https://mcp.tavily.com/mcp/", "", Duration.ofSeconds(1), 1,
                new TavilyWebSearchResultParser());
    }

    private static TavilySearchToolProvider fakeSearchProvider(String toolName) {
        return new TavilySearchToolProvider("unused", "unused", Duration.ofMillis(1), 1,
                new TavilyWebSearchResultParser()) {
            @Override
            public List<ToolCallback> toolCallbacks() {
                return List.of(new RecordingToolCallback(toolName, "fake search", "search result"));
            }
        };
    }

    /** 指向一个本机大概率没有监听的端口，短超时 + 单次尝试，快速失败走降级路径，不需要真的连 mcp-echarts。 */
    private static ChartToolProvider degradedChartProvider() {
        return new ChartToolProvider("http://localhost:1/mcp", Duration.ofMillis(200), 1);
    }

    /** 不连真实 mcp-echarts，直接返回一个固定的假图表工具——用于验证工厂层面的挂载/保护逻辑。 */
    private static ChartToolProvider fakeChartProvider(String toolName) {
        return new ChartToolProvider("unused", Duration.ofMillis(1), 1) {
            @Override
            public List<ToolCallback> toolCallbacks() {
                return List.of(new ChartToolCallback(
                        new RecordingToolCallback(toolName, "生成一张假图表", "http://localhost:9000/agenttrail-charts/fake.png")));
            }
        };
    }

    @Test
    void fallsBackToTheDefaultModelWhenNoModelIdIsGiven() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(deepSeek, qwen), "qwen-plus").build();

        String answer = factory.forModel(null).call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from qwen");
        assertThat(deepSeek.roundCount()).isZero();
    }

    @Test
    void blankModelIdAlsoFallsBackToTheDefault() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(deepSeek, qwen), "qwen-plus").build();

        String answer = factory.forModel("  ").call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from qwen");
    }

    @Test
    void routesToTheExplicitlyRequestedModel() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(deepSeek, qwen), "qwen-plus").build();

        String answer = factory.forModel("deepseek-chat").call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from deepseek");
        assertThat(qwen.roundCount()).isZero();
    }

    @Test
    void rejectsAnUnknownModelId() {
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(new ScriptedChatModel(List.of()), new ScriptedChatModel(List.of())), "qwen-plus").build();

        assertThatThrownBy(() -> factory.forModel("gpt-5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gpt-5");
    }

    @Test
    void rejectsAnUnregisteredDefaultModelAtConstructionTime() {
        List<RegisteredModel> models = List.of(
                new RegisteredModel("deepseek-chat", new ScriptedChatModel(List.of()), ThinkingMode.REASONING_CONTENT));

        assertThatThrownBy(() -> AgentLoopExecutorFactory.builder(models, "qwen-plus").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qwen-plus");
    }

    @Test
    void everyRegisteredModelSharesTheSameTaskManagerSoSingleFlightWorksAcrossModelSwitches() {
        AgentTaskManager sharedTaskManager = new AgentTaskManager();
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(twoModels(deepSeek, qwen), "qwen-plus")
                .taskManager(sharedTaskManager)
                .build();

        AgentLoopExecutor deepSeekExecutor = factory.forModel("deepseek-chat");
        AgentLoopExecutor qwenExecutor = factory.forModel("qwen-plus");

        // 两个执行器都在，且各自绑定了自己那台 ChatModel——用同一个 conversationId 分别调用
        // 两个模型都能各跑一轮，说明它们不是同一个执行器实例，也没有互相冲突的单飞状态残留。
        RunnableParams params = new RunnableParams("conv-shared", "user-1");
        assertThat(deepSeekExecutor.call("hi", params)).isEqualTo("from deepseek");
        assertThat(qwenExecutor.call("hi", params)).isEqualTo("from qwen");
    }

    @Test
    void webSearchEnabledFalseNeverConsultsTheSearchProviderEvenIfOneIsConfigured() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus")
                .webSearch(degradedSearchProvider())
                .build();

        AgentLoopExecutor plain = factory.forModel("qwen-plus", false);

        assertThat(plain).isSameAs(factory.forModel("qwen-plus"));
    }

    @Test
    void webSearchEnabledDegradesToThePlainExecutorWhenNoSearchProviderIsConfigured() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus").build();

        AgentLoopExecutor withSearch = factory.forModel("qwen-plus", true);

        assertThat(withSearch).isSameAs(factory.forModel("qwen-plus"));
    }

    @Test
    void webSearchEnabledDegradesGracefullyWhenTheProviderHasNoUsableKey() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus")
                .webSearch(degradedSearchProvider())
                .build();

        String answer = factory.forModel("qwen-plus", true).call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from qwen");
    }

    @Test
    void routesQwenWebSearchThroughTheNativeCompatibleModelBeforeToolChunksReachOpenAiSdk() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(twoModels(deepSeek, qwen), "qwen-plus")
                .webSearch(fakeSearchProvider("web_search"))
                .build();

        String answer = factory.forModel("qwen-plus", true).call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from deepseek");
        assertThat(qwen.roundCount()).isZero();
    }

    /**
     * 回归测试：文件工具挂载在 {@code plainExecutorsByModelId}（{@link #forModel(String)} 走的
     * 就是这条最常见路径，不需要开联网搜索），构造时如果不经过 {@code resolveToolCallingModel}
     * 就直接用请求方自己的 qwen-plus ChatModel 建执行器，工具调用分片会真的打到
     * {@code OpenAiChatModel} 里那个已知的 {@code Optional.get()} bug（踩坑点 #78a）——这个测试
     * 用 {@link ScriptedChatModel} 顶替不了那个真实 SDK 里的 bug，但能保证"请求走的确实是
     * deepseek-chat、不是 qwen-plus"这件事本身不会再回归。
     */
    @Test
    void routesPlainQwenConversationsThroughTheNativeCompatibleModelWhenTheFileToolIsMounted() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        FileContentTool fileContentTool = new FileContentTool(mock(FileQaService.class));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(twoModels(deepSeek, qwen), "qwen-plus")
                .fileContentTool(fileContentTool)
                .build();

        String answer = factory.forModel("qwen-plus").call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from deepseek");
        assertThat(qwen.roundCount()).isZero();
    }

    @Test
    void forModelWithChartsDegradesToThePlainExecutorWhenNoChartProviderIsConfigured() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus").build();

        AgentLoopExecutor withCharts = factory.forModelWithCharts("qwen-plus", false);

        assertThat(withCharts).isSameAs(factory.forModel("qwen-plus"));
    }

    @Test
    void forModelWithChartsDegradesGracefullyWhenMcpEchartsIsUnavailable() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus")
                .charts(degradedChartProvider())
                .build();

        String answer = factory.forModelWithCharts("qwen-plus", false)
                .call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from qwen");
    }

    @Test
    void forModelWithChartsMountsTheChartToolAndCachesTheResult() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus")
                .charts(fakeChartProvider("generate_bar_chart"))
                .build();

        AgentLoopExecutor withCharts = factory.forModelWithCharts("qwen-plus", false);

        assertThat(withCharts).as("图表工具真的挂上了，不应该退化成不带工具的执行器")
                .isNotSameAs(factory.forModel("qwen-plus"));
        assertThat(withCharts).as("第二次调用应该命中缓存")
                .isSameAs(factory.forModelWithCharts("qwen-plus", false));
    }

    @Test
    void routesQwenChartConversationsThroughTheNativeCompatibleModelToo() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(twoModels(deepSeek, qwen), "qwen-plus")
                .charts(fakeChartProvider("chart"))
                .build();

        String answer = factory.forModelWithCharts("qwen-plus", false)
                .call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from deepseek");
        assertThat(qwen.roundCount()).isZero();
    }

    private static final List<String> ANALYTICS_TOOLS = List.of(
            "list_tables", "describe_tables", "lookup_glossary", "validate_sql", "execute_sql", "calculate");

    private static AnalyticsToolProvider fakeAnalyticsProvider() {
        return new AnalyticsToolProvider(ANALYTICS_TOOLS.stream()
                .map(name -> (ToolCallback) new RecordingToolCallback(name, "fake " + name, "result"))
                .toList());
    }

    private static SkillManager skillManagerWith(ToolCallback skillTool) {
        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.buildSkillsTool()).thenReturn(Optional.ofNullable(skillTool));
        return skillManager;
    }

    /**
     * issue #95 的核心断言。这六个工具曾经全在 ToolSearch 的延迟池里，模型第一轮只看得到
     * {@code search_tools}——2026-08-05 的评测里多轮失败案例的 toolCalls 只有它，模型反复
     * 回答"没找到能查询数据的工具"。断言必须落在**第一轮**的工具清单上：延迟发现即便召回成功，
     * 工具也要到下一轮才出现，"第一轮就能看见"正是常驻和延迟的分界。
     */
    @Test
    void mountsEveryAnalyticsToolUpFrontInsteadOfBehindToolSearch() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(deepSeek, new ScriptedChatModel(List.of())), "deepseek-chat")
                .analytics(fakeAnalyticsProvider())
                .build();

        factory.forAnalytics(null).call("上个月的订单量", new RunnableParams("conv-1", "user-1"));

        assertThat(deepSeek.toolNamesAtRound(0))
                .as("六个分析工具必须在第一轮就对模型可见")
                .containsAll(ANALYTICS_TOOLS)
                .as("ToolSearch 不再挂在分析路径上——它是为工具多到撑爆上下文设计的，六个工具用它是错配")
                .doesNotContain("search_tools");
    }

    /**
     * DataAgent 的 SOP 是 {@code skills/data-analysis/SKILL.md}，只能通过 Skill 元工具加载。
     * 之前分析执行器的 builder 链上没有 skillManager，系统提示词实际只有日期区块——模型
     * 既不知道自己是数据分析 Agent，也不知道工具调用顺序（实测把"空结果不是错误"当成编程题回答）。
     */
    @Test
    void givesTheAnalyticsExecutorItsOwnSkillTool() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(deepSeek, new ScriptedChatModel(List.of())), "deepseek-chat")
                .analytics(fakeAnalyticsProvider())
                .skills(skillManagerWith(new RecordingToolCallback("Skill", "加载技能", "SOP 正文")))
                .build();

        factory.forAnalytics(null).call("上个月的订单量", new RunnableParams("conv-1", "user-1"));

        assertThat(deepSeek.toolNamesAtRound(0)).contains("Skill");
    }

    /** 没有技能启用时 buildSkillsTool 返回 empty，分析执行器照常工作——"这一轮没有技能"是正常状态。 */
    @Test
    void keepsTheAnalyticsExecutorWorkingWhenNoSkillIsEnabled() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(deepSeek, new ScriptedChatModel(List.of())), "deepseek-chat")
                .analytics(fakeAnalyticsProvider())
                .skills(skillManagerWith(null))
                .build();

        String answer = factory.forAnalytics(null).call("上个月的订单量", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("done");
        assertThat(deepSeek.toolNamesAtRound(0)).containsAll(ANALYTICS_TOOLS).doesNotContain("Skill");
    }

    /**
     * 缓存判据是"图表工具真的挂上了"，不能用 residentTools——它现在恒非空（六个分析工具常驻），
     * 拿它判断会把 mcp-echarts 不可用时的降级执行器也缓存下来，服务恢复后所有新分析会话
     * 仍然一个绘图工具都没有，直到应用重启。
     */
    @Test
    void refusesToCacheAnAnalyticsExecutorBuiltWhileChartsWereDown() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(), List.of());
        AgentLoopExecutorFactory factory = AgentLoopExecutorFactory.builder(
                        twoModels(deepSeek, new ScriptedChatModel(List.of())), "deepseek-chat")
                .charts(degradedChartProvider())
                .analytics(fakeAnalyticsProvider())
                .build();

        assertThat(factory.forAnalytics(null))
                .as("图表降级时构造的执行器不该被缓存，否则 mcp-echarts 恢复后也换不回来")
                .isNotSameAs(factory.forAnalytics(null));
    }

}
