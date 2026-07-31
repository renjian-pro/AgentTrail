package com.agenttrail.loop.stageoutput;

import com.agenttrail.loop.model.AgentStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class StageOutputManagerTest {

    private static final StageContext CONTEXT = new StageContext("问题", null, null);

    @Test
    void emptyManagerIsANoOpForAllThreeHooks() {
        List<AgentStreamEvent> emitted = new ArrayList<>();

        StageOutputManager.EMPTY.afterStart(CONTEXT, emitted::add);
        StageOutputManager.EMPTY.afterToolEnd(CONTEXT, emitted::add);
        StageOutputManager.EMPTY.beforeComplete(CONTEXT, emitted::add);

        assertThat(emitted).isEmpty();
    }

    @Test
    void constructingWithAnEmptyOrNullListIsAlsoANoOp() {
        List<AgentStreamEvent> emitted = new ArrayList<>();

        new StageOutputManager(List.of()).afterStart(CONTEXT, emitted::add);
        new StageOutputManager(null).afterStart(CONTEXT, emitted::add);

        assertThat(emitted).isEmpty();
    }

    @Test
    void onlyInvokesProvidersRegisteredForTheMatchingTiming() {
        List<AgentStreamEvent> emitted = new ArrayList<>();
        StageOutputManager manager = new StageOutputManager(List.of(
                providerFor("welcome", StageTiming.AFTER_START, ctx -> "hi"),
                providerFor("reference", StageTiming.BEFORE_COMPLETE, ctx -> "refs")));

        manager.afterStart(CONTEXT, emitted::add);

        assertThat(emitted).containsExactly(new AgentStreamEvent.StageOutput("welcome", "hi"));
    }

    @Test
    void multipleProvidersForTheSameTimingAllRun() {
        List<AgentStreamEvent> emitted = new ArrayList<>();
        StageOutputManager manager = new StageOutputManager(List.of(
                providerFor("a", StageTiming.AFTER_TOOL_END, ctx -> "1"),
                providerFor("b", StageTiming.AFTER_TOOL_END, ctx -> "2")));

        manager.afterToolEnd(CONTEXT, emitted::add);

        assertThat(emitted).containsExactly(
                new AgentStreamEvent.StageOutput("a", "1"),
                new AgentStreamEvent.StageOutput("b", "2"));
    }

    @Test
    void aProviderReturningNullProducesNoEvent() {
        List<AgentStreamEvent> emitted = new ArrayList<>();
        StageOutputManager manager = new StageOutputManager(
                List.of(providerFor("silent", StageTiming.BEFORE_COMPLETE, ctx -> null)));

        manager.beforeComplete(CONTEXT, emitted::add);

        assertThat(emitted).isEmpty();
    }

    /** 一个 provider 出异常不该拖垮同一个钩子点上的其它 provider。 */
    @Test
    void aFailingProviderDoesNotPreventOtherProvidersFromRunning() {
        List<AgentStreamEvent> emitted = new ArrayList<>();
        StageOutputProvider failing = providerFor("broken", StageTiming.AFTER_START, ctx -> {
            throw new RuntimeException("boom");
        });
        StageOutputManager manager = new StageOutputManager(
                List.of(failing, providerFor("ok", StageTiming.AFTER_START, ctx -> "fine")));

        assertThatCode(() -> manager.afterStart(CONTEXT, emitted::add)).doesNotThrowAnyException();

        assertThat(emitted).containsExactly(new AgentStreamEvent.StageOutput("ok", "fine"));
    }

    @Test
    void contextIsPassedThroughToTheProvider() {
        StageContext[] received = new StageContext[1];
        StageOutputManager manager = new StageOutputManager(List.of(
                providerFor("capture", StageTiming.BEFORE_COMPLETE, ctx -> {
                    received[0] = ctx;
                    return "captured";
                })));
        StageContext context = new StageContext("问题", "答案", null);

        manager.beforeComplete(context, event -> { });

        assertThat(received[0]).isSameAs(context);
    }

    private static StageOutputProvider providerFor(
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
