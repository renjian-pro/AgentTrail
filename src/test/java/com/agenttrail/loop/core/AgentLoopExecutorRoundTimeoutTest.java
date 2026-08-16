package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ChatResponses;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.task.AgentTaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 踩坑点 #93：2026-08-06 用一次真实 DashScope 卡顿复现过——模型文字已经吐完，但连接持续零星
 * 发一些不产出可见内容的收尾帧（{@code ChatResponses.usage(...)} 这类只带 token 统计、
 * {@code getResult()} 为 null 的 chunk，{@code processChunk()} 会直接 return，前端看不到任何
 * 输出）。这类 chunk 对 {@link LlmInvoker} 的 idle 超时来说仍然是"收到了新信号"，30 秒窗口被
 * 无限重置，永远不会真正超时——单飞锁因此永久卡住。这里锁住 {@link AgentLoopExecutor} 新加的
 * 绝对时钟兜底：不管上游发生什么，到点了就强制收尾，释放锁。
 */
class AgentLoopExecutorRoundTimeoutTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void abandonsARoundThatKeepsEmittingContentlessChunksWithoutEverCompleting() {
        AgentTaskManager taskManager = new AgentTaskManager();
        ChatModel neverCompletes = chatModelReturning(Flux.concat(
                Mono.just(ChatResponses.text("我需要先查一下")),
                Flux.interval(Duration.ofMillis(20)).map(tick -> ChatResponses.usage(1, 1))));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(neverCompletes, List.of(), 5)
                .taskManager(taskManager)
                .modelName("test")
                .roundTimeout(Duration.ofMillis(200))
                .build();

        long startedAt = System.currentTimeMillis();
        assertThatThrownBy(() -> executor.call("请分析 film_actor 表", new RunnableParams("conv-1", "user-1")))
                .isInstanceOf(AgentCallException.class)
                .extracting(ex -> ((AgentCallException) ex).code())
                .isEqualTo("LLM_CALL_FAILED");
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertThat(elapsedMillis)
                .as("调用方必须在配置的绝对超时附近拿回控制权，不能被无内容 chunk 一直吊着")
                .isLessThan(2000);
        assertThat(taskManager.hasRunningTask("conv-1"))
                .as("超时收尾必须释放单飞锁，否则同一会话之后永远拿到 CONCURRENT_EXECUTION")
                .isFalse();
    }

    /** 正常快速完成的轮次不应该被绝对超时误伤——watchdog 到点后发现订阅已经自然结束，直接放行。 */
    @Test
    void doesNotInterfereWithARoundThatFinishesWellBeforeTheAbsoluteTimeout() {
        ChatModel fast = chatModelReturning(Flux.just(ChatResponses.text("你好")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(fast, List.of(), 5)
                .modelName("test")
                .roundTimeout(Duration.ofMillis(200))
                .build();

        String answer = executor.call("你好", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("你好");
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
