package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ChatResponses;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.persistence.TurnRecord;
import com.agenttrail.loop.task.AgentTaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户按下停止之后，这一轮必须**落库**。
 *
 * <p>此前不落：停止走的是 {@code AgentTaskManager.stopLocalTask} → {@code dispose()}，
 * 轮次订阅被取消，{@code doOnComplete}/{@code doOnError} 都不会触发，唯一调
 * {@code turnCommitter.commit} 的 {@code completeRun} 因此永远走不到。表现是——问题和已经
 * 吐出来的半截答案在界面上看着好好的（那是前端内存里的），一旦切到别的会话再切回来，
 * 前端用服务端历史整体替换消息列表，这一轮凭空消失，用户会觉得"我明明问过"。
 */
class AgentLoopExecutorStopPersistenceTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void keepsTheHalfFinishedTurnWhenTheUserStopsMidStream() throws Exception {
        AgentTaskManager taskManager = new AgentTaskManager();
        List<TurnRecord> persisted = new CopyOnWriteArrayList<>();
        // 第一个 chunk 之后就挂着不结束，模拟"模型还在吐，用户等不及按了停止"
        ChatModel stillTyping = chatModelReturning(Flux.concat(
                Mono.just(ChatResponses.text("上海今天多云，")),
                Flux.never()));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(stillTyping, List.of(), 5)
                .taskManager(taskManager)
                .persistenceHook(recordingHook(persisted))
                .modelName("test")
                // 绝对超时要远大于本用例的耗时，否则停下这一轮的是看门狗而不是用户
                .roundTimeout(Duration.ofSeconds(30))
                .build();

        CountDownLatch firstText = new CountDownLatch(1);
        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        Disposable subscription = executor.stream("上海天气如何", new RunnableParams("conv-stop", "user-1"))
                .doOnNext(event -> {
                    events.add(event);
                    if (event instanceof AgentStreamEvent.Text) firstText.countDown();
                })
                .subscribe();
        assertThat(firstText.await(3, TimeUnit.SECONDS)).as("模型应当先吐出一段正文").isTrue();

        assertThat(taskManager.stopTask("conv-stop")).isTrue();

        assertThat(persisted).singleElement().satisfies(record -> {
            assertThat(record.question()).isEqualTo("上海天气如何");
            assertThat(record.answer())
                    .as("已经吐出来的半截答案要留住，用户看到过它")
                    .isEqualTo("上海今天多云，");
        });
        assertThat(taskManager.hasRunningTask("conv-stop"))
                .as("中断收尾同样要释放单飞占位，否则这个会话之后一直是 CONCURRENT_EXECUTION")
                .isFalse();
        subscription.dispose();
    }

    /** 一个字都还没吐出来就被停掉：问题本身仍然要留住，答案写 null（而不是空串）。 */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void keepsTheQuestionEvenWhenNothingWasGeneratedYet() {
        AgentTaskManager taskManager = new AgentTaskManager();
        List<TurnRecord> persisted = new CopyOnWriteArrayList<>();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModelReturning(Flux.never()), List.of(), 5)
                .taskManager(taskManager)
                .persistenceHook(recordingHook(persisted))
                .modelName("test")
                .roundTimeout(Duration.ofSeconds(30))
                .build();

        Disposable subscription = executor.stream("上海天气如何", new RunnableParams("conv-stop", "user-1")).subscribe();
        taskManager.stopTask("conv-stop");

        assertThat(persisted).singleElement().satisfies(record -> {
            assertThat(record.question()).isEqualTo("上海天气如何");
            assertThat(record.answer())
                    .as("空串会让 loadHistory 给下一轮回放一条内容为空的助手消息")
                    .isNull();
        });
        subscription.dispose();
    }

    /**
     * 超时也是 {@code dispose()}，走的是同一个 CANCEL 信号——但它有自己的收尾（Error 事件 +
     * 失败审计）。两套收尾都跑的话，同一轮会既落库又报错、还发两次 Complete。
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void leavesTheTimeoutPathAlone() throws Exception {
        AgentTaskManager taskManager = new AgentTaskManager();
        List<TurnRecord> persisted = new CopyOnWriteArrayList<>();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModelReturning(Flux.never()), List.of(), 5)
                .taskManager(taskManager)
                .persistenceHook(recordingHook(persisted))
                .modelName("test")
                .roundTimeout(Duration.ofMillis(150))
                .build();

        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.stream("上海天气如何", new RunnableParams("conv-timeout", "user-1"))
                .doOnNext(events::add)
                .doFinally(signal -> done.countDown())
                .subscribe();

        assertThat(done.await(3, TimeUnit.SECONDS)).as("看门狗应当收掉这一轮").isTrue();
        assertThat(events).filteredOn(AgentStreamEvent.Error.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(AgentStreamEvent.Complete.class::isInstance)
                .as("超时只该有它自己那一套收尾，不能再叠一次中断收尾")
                .isEmpty();
        assertThat(persisted).isEmpty();
    }

    /**
     * 取消信号不止"用户按了停止"一个来源——轮次订阅在 {@code AgentTaskManager.setDisposable}
     * 发现任务已经不在时也会被当作孤儿 dispose 掉。已经正常收尾（或已经暂停等审批）的运行
     * 如果再走一遍中断收尾，同一轮问答会落库两次，用户在历史里看到自己问了两遍。
     */
    @Test
    void letsOnlyOneTerminalPathClaimTheRun() {
        RunContext context = new RunContext("上海天气如何", new RunnableParams("conv-1", "user-1"),
                new ArrayList<>(), EventSinks.bounded(), new AtomicInteger(0), System.currentTimeMillis(),
                null, null);

        assertThat(context.markTerminated()).isTrue();
        assertThat(context.markTerminated()).isFalse();
    }

    private static TurnPersistenceHook recordingHook(List<TurnRecord> sink) {
        return new TurnPersistenceHook() {
            @Override
            public Long onTurnComplete(TurnRecord record) {
                sink.add(record);
                return (long) sink.size();
            }

            @Override
            public List<Message> loadHistory(String conversationId, int tokenBudget) {
                return new ArrayList<>();
            }
        };
    }

    private static ChatModel chatModelReturning(Flux<ChatResponse> stream) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本类只测流式调用");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return stream;
            }
        };
    }
}
