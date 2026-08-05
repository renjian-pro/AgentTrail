package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.agenttrail.loop.hook.AgentHooks;
import com.agenttrail.loop.hook.BudgetHook;
import com.agenttrail.loop.hook.OnErrorHook;
import com.agenttrail.loop.hook.PostToolUseHook;
import com.agenttrail.loop.hook.PreToolUseHook;
import com.agenttrail.loop.hook.SessionEndHook;
import com.agenttrail.loop.hook.SessionStartHook;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.InMemoryPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.ResumeInstruction;
import com.agenttrail.loop.task.AgentTaskManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static com.agenttrail.loop.core.support.ChatResponses.usage;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopExecutorHooksTest {

    @Test
    void textRunFiresSessionStartBudgetAndSessionEndOnly() {
        List<String> events = new ArrayList<>();
        AgentHooks hooks = hooks(events);
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("done"), usage(11, 7)));

        AgentLoopExecutor executor = AgentLoopExecutor.builder(model, List.of(), 5)
                .hooks(hooks)
                .build();

        executor.stream("hello", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).containsExactly("start:conv-1:0", "budget:conv-1:1:11:7", "end:conv-1:1:true");
    }

    @Test
    void toolRunFiresPreAndPostForEveryExecutedToolAndBudgetForEachRound() {
        List<String> events = new ArrayList<>();
        RecordingToolCallback echo = new RecordingToolCallback("echo", "echo", "pong");
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{}")),
                List.of(text("done")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model, List.of(echo), 5)
                .hooks(hooks(events))
                .build();

        executor.stream("echo", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).containsExactly(
                "start:conv-1:0",
                "budget:conv-1:1:0:0",
                "pre:conv-1:1:echo",
                "post:conv-1:1:echo:true:pong",
                "budget:conv-1:2:0:0",
                "end:conv-1:2:true");
    }

    @Test
    void approvalPauseDoesNotEndSessionOrFirePreHookUntilApproved() {
        List<String> events = new ArrayList<>();
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback charge = new RecordingToolCallback("chargeCard", "charge", "charged");
        PauseConfig pauseConfig = new PauseConfig(Set.of("chargeCard"), store);
        ScriptedChatModel pauseModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "chargeCard", "{\"amount\":1}")));
        AgentLoopExecutor pausingExecutor = AgentLoopExecutor.builder(pauseModel, List.of(charge), 5)
                .pauseConfig(pauseConfig)
                .hooks(hooks(events))
                .build();

        pausingExecutor.stream("charge", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).containsExactly("start:conv-1:0", "budget:conv-1:1:0:0");
        assertThat(charge.recordedArguments()).isEmpty();

        ScriptedChatModel resumeModel = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutor resumingExecutor = AgentLoopExecutor.builder(resumeModel, List.of(charge), 5)
                .pauseConfig(pauseConfig)
                .hooks(hooks(events))
                .build();
        resumingExecutor.resume("conv-1", ResumeInstruction.ApprovalDecision.approve())
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).contains("pre:conv-1:1:chargeCard", "post:conv-1:1:chargeCard:true:charged");
        assertThat(events).contains("end:conv-1:2:true");
    }

    @Test
    void modelFailureFiresOnErrorAndFailedSessionEnd() {
        List<String> events = new ArrayList<>();
        ScriptedChatModel model = new ScriptedChatModel(List.of()) {
            @Override
            public Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                return Flux.error(new IllegalStateException("boom"));
            }
        };
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model, List.of(), 5)
                .hooks(hooks(events))
                .build();

        List<AgentStreamEvent> result = executor.stream("hello", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(result).contains(new AgentStreamEvent.Error("LLM_CALL_FAILED", "boom"));
        assertThat(events).containsExactly(
                "start:conv-1:0", "error:conv-1:1:boom", "end:conv-1:1:false");
    }

    @Test
    void recordsIndependentLlmDurationAndTtftTimersWithModelTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScriptedChatModel model = new ScriptedChatModel(List.of(text("done"), usage(11, 7)));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model, List.of(), 5)
                .meterRegistry(registry)
                .modelName("test-model")
                .build();

        executor.stream("hello", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(registry.get("agenttrail.llm.duration").timer("model", "test-model").count()).isEqualTo(1);
        assertThat(registry.get("agenttrail.llm.ttft").timer("model", "test-model").count()).isEqualTo(1);
    }

    private static AgentHooks hooks(List<String> events) {
        SessionStartHook sessionStart = context -> events.add("start:" + context.conversationId() + ":" + context.round());
        PreToolUseHook preTool = (context, invocation) ->
                events.add("pre:" + context.conversationId() + ":" + context.round() + ":" + invocation.toolName());
        PostToolUseHook postTool = (context, invocation, result, success) -> events.add(
                "post:" + context.conversationId() + ":" + context.round() + ":" + invocation.toolName()
                        + ":" + success + ":" + result);
        BudgetHook budget = (context, prompt, completion) -> events.add(
                "budget:" + context.conversationId() + ":" + context.round() + ":" + prompt + ":" + completion);
        OnErrorHook error = (context, failure) -> events.add(
                "error:" + context.conversationId() + ":" + context.round() + ":" + failure.getMessage());
        SessionEndHook end = (context, success) -> events.add(
                "end:" + context.conversationId() + ":" + context.round() + ":" + success);
        return new AgentHooks(List.of(sessionStart), List.of(preTool), List.of(postTool), List.of(budget),
                List.of(error), List.of(end));
    }
}
