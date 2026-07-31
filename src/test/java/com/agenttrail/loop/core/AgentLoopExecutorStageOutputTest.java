package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.stageoutput.StageContext;
import com.agenttrail.loop.stageoutput.StageOutputManager;
import com.agenttrail.loop.stageoutput.StageOutputProvider;
import com.agenttrail.loop.stageoutput.StageTiming;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #16：三个钩子点必须在循环真实运行时被打到正确的时机上，光测
 * {@code StageOutputManager} 自己的调用逻辑不够——这里用一次"工具调用轮 + 收尾轮"的完整对话
 * 验证 AFTER_START 在第一次模型调用前、AFTER_TOOL_END 在工具批次跑完后、BEFORE_COMPLETE
 * 在 Complete 之前，且各自只触发一次。
 */
class AgentLoopExecutorStageOutputTest {

    @Test
    void firesAllThreeHooksAtTheRightPointsInASingleRun() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "echoes", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{}")),
                List.of(text("done"))
        );
        List<String> observedOrder = new ArrayList<>();
        AtomicReference<String> beforeCompleteAnswer = new AtomicReference<>();

        StageOutputManager manager = new StageOutputManager(List.of(
                recordingProvider("welcome", StageTiming.AFTER_START, ctx -> {
                    observedOrder.add("AFTER_START");
                    return "hi";
                }),
                recordingProvider("toolSummary", StageTiming.AFTER_TOOL_END, ctx -> {
                    observedOrder.add("AFTER_TOOL_END");
                    return "tool ran";
                }),
                recordingProvider("reference", StageTiming.BEFORE_COMPLETE, ctx -> {
                    observedOrder.add("BEFORE_COMPLETE");
                    beforeCompleteAnswer.set(ctx.answer());
                    return "refs";
                })));

        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(echoTool), 5)
                .stageOutputManager(manager)
                .build();

        List<AgentStreamEvent> events = executor.stream("echo something", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(observedOrder).containsExactly("AFTER_START", "AFTER_TOOL_END", "BEFORE_COMPLETE");
        assertThat(beforeCompleteAnswer.get()).as("BEFORE_COMPLETE 的 context 必须带上最终答案").isEqualTo("done");
        assertThat(events).contains(
                new AgentStreamEvent.StageOutput("welcome", "hi"),
                new AgentStreamEvent.StageOutput("toolSummary", "tool ran"),
                new AgentStreamEvent.StageOutput("reference", "refs"));
        // StageOutput 必须在 Complete 之前，不是之后——beforeComplete 这个名字不是摆设
        assertThat(events.indexOf(new AgentStreamEvent.StageOutput("reference", "refs")))
                .isLessThan(events.indexOf(events.stream()
                        .filter(AgentStreamEvent.Complete.class::isInstance).findFirst().orElseThrow()));
    }

    @Test
    void doesNotFireAfterToolEndWhenTheRoundHasNoToolCalls() {
        List<String> firedHooks = new ArrayList<>();
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("直接回答，没有工具调用")));
        StageOutputManager manager = new StageOutputManager(List.of(
                recordingProvider("toolSummary", StageTiming.AFTER_TOOL_END, ctx -> {
                    firedHooks.add("AFTER_TOOL_END");
                    return "should not appear";
                })));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .stageOutputManager(manager)
                .build();

        executor.stream("你好", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(firedHooks).isEmpty();
    }

    /** 没配 StageOutputManager（传 null）时行为必须和没有这个机制时完全一致。 */
    @Test
    void behavesNormallyWhenNoStageOutputManagerIsConfigured() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        List<AgentStreamEvent> events = executor.stream("hi", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).noneMatch(AgentStreamEvent.StageOutput.class::isInstance);
    }

    private static StageOutputProvider recordingProvider(
            String name, StageTiming timing, java.util.function.Function<StageContext, Object> produce) {
        return new StageOutputProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public StageTiming timing() {
                return timing;
            }

            @Override
            public Object produce(StageContext context) {
                return produce.apply(context);
            }
        };
    }
}
