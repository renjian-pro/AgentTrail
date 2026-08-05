package com.agenttrail.loop.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTraceStoreTest {

    @Test
    void unknownConversationReturnsAnEmptyList() {
        InMemoryTraceStore store = new InMemoryTraceStore();

        assertThat(store.findByConversationId("nope")).isEmpty();
    }

    @Test
    void recordsForTheSameConversationAccumulateInSaveOrder() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecord first = record("conv-1", 1);
        TraceRecord second = record("conv-1", 2);

        store.save(first);
        store.save(second);

        assertThat(store.findByConversationId("conv-1")).containsExactly(first, second);
    }

    @Test
    void recordsAreKeyedPerConversation() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecord own = record("conv-1", 1);

        store.save(own);
        store.save(record("conv-2", 1));

        assertThat(store.findByConversationId("conv-1")).containsExactly(own);
    }

    @Test
    void inMemoryStoreHasNoPersistedChainToVerify() {
        assertThat(new InMemoryTraceStore().verifyChain("conv-1")).isEmpty();
    }

    private static TraceRecord record(String conversationId, int round) {
        return new TraceRecord(conversationId, round, "input", "output", null,
                10, 5, 100, true, null, System.currentTimeMillis());
    }
}
