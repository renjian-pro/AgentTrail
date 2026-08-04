package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.memory.InMemoryMemoryStore;
import com.agenttrail.loop.memory.MemoryItem;
import com.agenttrail.loop.memory.MemoryType;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #19：已有记忆时要作为 {@link SystemMessage} 注入到历史最前面；一轮结束后要触发提取
 * （提取本身用另一次模型调用，这里通过 {@link ScriptedChatModel} 的第二轮脚本模拟）；
 * 没配置 {@code MemoryStore} 时两件事都不发生。
 */
class AgentLoopExecutorMemoryTest {

    @Test
    void injectsExistingMemoryAsALeadingSystemMessage() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.save(new MemoryItem("user-1", MemoryType.PROFILE, "产品经理", 1L));
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("你好")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .memoryStore(store)
                .build();

        executor.stream("你好", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        // index 0 是无条件注入的当前日期系统消息（见 AgentLoopExecutor#buildDateSection），
        // 记忆区块紧跟在它后面
        List<Message> sentMessages = chatModel.messagesAtRound(0);
        assertThat(sentMessages.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(sentMessages.get(1).getText()).contains("长期记忆").contains("产品经理");
    }

    @Test
    void doesNotInjectASystemMessageWhenThereIsNoExistingMemory() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("你好")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .memoryStore(store)
                .build();

        executor.stream("你好", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        // index 0 永远是日期消息；没有记忆时它后面直接就是 UserMessage，不会插入第二条 SystemMessage
        assertThat(chatModel.messagesAtRound(0).get(1)).isNotInstanceOf(SystemMessage.class);
    }

    @Test
    void extractsAndSavesMemoryAfterTheTurnCompletes() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        // 循环本身走 stream()（"好的，记住了"）；MemoryExtractor 内部走独立的 call()，
        // 不能共用 ScriptedChatModel——它的 call() 直接抛异常，只支持流式调用
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return text("[{\"type\":\"PROFILE\",\"content\":\"产品经理\"}]");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(text("好的，记住了"));
            }
        };
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .memoryStore(store)
                .build();

        executor.stream("我是产品经理", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(store.findByUserId("user-1")).extracting(MemoryItem::content).containsExactly("产品经理");
    }

    @Test
    void doesNothingWhenNoMemoryStoreIsConfigured() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("你好")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        executor.stream("你好", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.roundCount()).as("没配置记忆机制时不该触发额外的提取调用").isEqualTo(1);
        assertThat(chatModel.messagesAtRound(0).get(1)).isNotInstanceOf(SystemMessage.class);
    }
}
