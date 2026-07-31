package com.agenttrail.loop.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMemoryStoreTest {

    @Test
    void unknownUserReturnsAnEmptyList() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        assertThat(store.findByUserId("nope")).isEmpty();
    }

    @Test
    void itemsForTheSameUserAccumulateInSaveOrder() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryItem first = new MemoryItem("user-1", MemoryType.PROFILE, "产品经理", 1L);
        MemoryItem second = new MemoryItem("user-1", MemoryType.PREFERENCE, "偏好中文回复", 2L);

        store.save(first);
        store.save(second);

        assertThat(store.findByUserId("user-1")).containsExactly(first, second);
    }

    @Test
    void itemsAreKeyedPerUser() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryItem own = new MemoryItem("user-1", MemoryType.FACT, "使用 MySQL 8.0", 1L);

        store.save(own);
        store.save(new MemoryItem("user-2", MemoryType.FACT, "使用 PostgreSQL", 1L));

        assertThat(store.findByUserId("user-1")).containsExactly(own);
    }
}
