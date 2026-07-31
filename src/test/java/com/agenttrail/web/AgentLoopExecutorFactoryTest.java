package com.agenttrail.web;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.tools.websearch.TavilySearchToolProvider;
import com.agenttrail.loop.tools.websearch.TavilyWebSearchResultParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void fallsBackToTheDefaultModelWhenNoModelIdIsGiven() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(deepSeek, qwen), "qwen-plus", new AgentTaskManager(), null);

        String answer = factory.forModel(null).call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from qwen");
        assertThat(deepSeek.roundCount()).isZero();
    }

    @Test
    void blankModelIdAlsoFallsBackToTheDefault() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(deepSeek, qwen), "qwen-plus", new AgentTaskManager(), null);

        String answer = factory.forModel("  ").call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from qwen");
    }

    @Test
    void routesToTheExplicitlyRequestedModel() {
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(deepSeek, qwen), "qwen-plus", new AgentTaskManager(), null);

        String answer = factory.forModel("deepseek-chat").call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from deepseek");
        assertThat(qwen.roundCount()).isZero();
    }

    @Test
    void rejectsAnUnknownModelId() {
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(new ScriptedChatModel(List.of()), new ScriptedChatModel(List.of())),
                "qwen-plus", new AgentTaskManager(), null);

        assertThatThrownBy(() -> factory.forModel("gpt-5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gpt-5");
    }

    @Test
    void rejectsAnUnregisteredDefaultModelAtConstructionTime() {
        List<RegisteredModel> models = List.of(
                new RegisteredModel("deepseek-chat", new ScriptedChatModel(List.of()), ThinkingMode.REASONING_CONTENT));

        assertThatThrownBy(() -> new AgentLoopExecutorFactory(models, "qwen-plus", new AgentTaskManager(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qwen-plus");
    }

    @Test
    void everyRegisteredModelSharesTheSameTaskManagerSoSingleFlightWorksAcrossModelSwitches() {
        AgentTaskManager sharedTaskManager = new AgentTaskManager();
        ScriptedChatModel deepSeek = new ScriptedChatModel(List.of(text("from deepseek")));
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(deepSeek, qwen), "qwen-plus", sharedTaskManager, null);

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
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus", new AgentTaskManager(),
                degradedSearchProvider());

        AgentLoopExecutor plain = factory.forModel("qwen-plus", false);

        assertThat(plain).isSameAs(factory.forModel("qwen-plus"));
    }

    @Test
    void webSearchEnabledDegradesToThePlainExecutorWhenNoSearchProviderIsConfigured() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus", new AgentTaskManager(), null);

        AgentLoopExecutor withSearch = factory.forModel("qwen-plus", true);

        assertThat(withSearch).isSameAs(factory.forModel("qwen-plus"));
    }

    @Test
    void webSearchEnabledDegradesGracefullyWhenTheProviderHasNoUsableKey() {
        ScriptedChatModel qwen = new ScriptedChatModel(List.of(text("from qwen")));
        AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(
                twoModels(new ScriptedChatModel(List.of()), qwen), "qwen-plus", new AgentTaskManager(),
                degradedSearchProvider());

        String answer = factory.forModel("qwen-plus", true).call("hi", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("from qwen");
    }
}
