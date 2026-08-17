package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.prompt.PromptDefinition;
import com.agenttrail.loop.trace.InMemoryTraceStore;
import com.agenttrail.loop.trace.TraceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #111 / R22 验收的后半句：模式级系统提示词**要计入 {@code agent_trace.prompt_stamps}**。
 *
 * <h2>为什么这半句不能省</h2>
 *
 * {@code prompt_stamps} 这一列是 issue #101 专门建的，目的只有一个：Golden 分数变化时能回答
 * "是不是改提示词改出来的"。角色提示词恰恰是最容易被反复调、也最容易影响分数的那一份——
 * 它进不了 trace，这一列对主对话轮次就永远是空的，归因能力等于零。
 *
 * <p>光断言 {@code PromptRegistry.get(id).stamp()} 非空是不够的：那测的是注册表，不是接线。
 * 这里从执行器一路跑到 {@code TraceRecord}，测的是真的接上了。
 */
class AgentLoopExecutorPromptStampTest {

    private static final PromptDefinition ROLE_PROMPT =
            new PromptDefinition("chat.system", "v1", "deadbeef", "你是 AgentTrail 的通用助手。");

    @Test
    @DisplayName("模式级提示词的 stamp 落进本轮 trace")
    void writesTheModePromptStampIntoTheTraceRecord() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .systemPrompt(ROLE_PROMPT)
                .traceStore(traceStore)
                .build();

        executor.stream("你好", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(traceStore.findByConversationId("conv-1"))
                .isNotEmpty()
                .allSatisfy(record -> assertThat(record.promptStamps())
                        .as("每一轮都该带上角色提示词的 id@version#hash")
                        .contains("chat.system@v1#deadbeef"));
    }

    /**
     * 不挂角色提示词的装配（内部编排子调用）保持原样：{@code promptStamps} 为 null，
     * 而不是一个空串——{@code JdbcTraceStore.computeHash} 对 null 走旧 payload，
     * 存量会话的哈希链校验才不会全红（issue #101 的取舍）。
     */
    @Test
    @DisplayName("不挂角色提示词时 promptStamps 仍是 null，不影响存量哈希链")
    void leavesPromptStampsNullWhenNoModePromptIsMounted() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .traceStore(traceStore)
                .build();

        executor.stream("你好", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(traceStore.findByConversationId("conv-1"))
                .isNotEmpty()
                .allSatisfy(record -> assertThat(record.promptStamps()).isNull());
    }

    /** 正文照旧要真的进系统消息——stamp 接对了但正文没挂上，等于白做。 */
    @Test
    @DisplayName("正文仍然作为首条系统消息进入上下文")
    void stillPutsThePromptTextFirstInTheContext() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("好的")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .systemPrompt(ROLE_PROMPT)
                .build();

        executor.stream("你好", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        List<Message> sent = chatModel.messagesAtRound(0);
        assertThat(sent.get(0))
                .as("角色定义要排在日期/记忆/文件这些背景区块之前")
                .isInstanceOf(SystemMessage.class);
        assertThat(sent.get(0).getText()).isEqualTo("你是 AgentTrail 的通用助手。");
    }
}
