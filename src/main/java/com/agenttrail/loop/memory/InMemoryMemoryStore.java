package com.agenttrail.loop.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内存版 {@link MemoryStore}——每个用户一份追加写的列表。 */
public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, List<MemoryItem>> itemsByUserId = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryItem item) {
        itemsByUserId.computeIfAbsent(item.userId(), ignored -> new CopyOnWriteArrayList<>()).add(item);
    }

    @Override
    public List<MemoryItem> findByUserId(String userId) {
        return List.copyOf(itemsByUserId.getOrDefault(userId, List.of()));
    }
}
