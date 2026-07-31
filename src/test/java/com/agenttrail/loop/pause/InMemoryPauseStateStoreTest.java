package com.agenttrail.loop.pause;

import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPauseStateStoreTest {

    private final InMemoryPauseStateStore store = new InMemoryPauseStateStore();

    @Test
    void findsNothingForAConversationThatWasNeverPaused() {
        assertThat(store.find("never-paused")).isEmpty();
    }

    @Test
    void readsBackExactlyWhatWasSaved() {
        PauseState state = pauseStateFor("conv-1");

        store.save(state);

        assertThat(store.find("conv-1")).contains(state);
    }

    @Test
    void savingAgainForTheSameConversationOverwritesTheOldSnapshot() {
        store.save(pauseStateFor("conv-1"));
        PauseState updated = pauseStateFor("conv-1");

        store.save(updated);

        assertThat(store.find("conv-1")).contains(updated);
    }

    @Test
    void deleteReturnsTrueOnlyWhenSomethingWasActuallyRemoved() {
        store.save(pauseStateFor("conv-1"));

        assertThat(store.delete("conv-1")).isTrue();
        assertThat(store.delete("conv-1")).isFalse();
        assertThat(store.find("conv-1")).isEmpty();
    }

    @Test
    void keepsDifferentConversationsIndependent() {
        store.save(pauseStateFor("conv-1"));
        store.save(pauseStateFor("conv-2"));

        store.delete("conv-1");

        assertThat(store.find("conv-1")).isEmpty();
        assertThat(store.find("conv-2")).isPresent();
    }

    private static PauseState pauseStateFor(String conversationId) {
        return new PauseState(conversationId, List.of(new UserMessage("hi")),
                List.of(new PendingToolCall("call-1", "charge", "{}")),
                PauseReason.HITL_APPROVAL, SafePoint.BEFORE_TOOL_EXECUTION,
                "hi", new RunnableParams(conversationId, "user-1"), 1, System.currentTimeMillis());
    }
}
