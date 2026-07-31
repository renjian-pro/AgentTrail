package com.agenttrail.loop.file;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内存版 {@link FileStore}——无需外部存储即可跑通全流程，主键用自增序号模拟。
 *
 * <p>只维护一份 {@code id -> UploadedFile} 的映射：按会话查找靠过滤 + 按 id 排序，
 * 不另外维护一份"会话 -> 文件列表"的副本——record 不可变，{@link #updateParsedText}
 * 每次都要整体替换条目，两份索引各更新一次容易漏掉一处导致读到旧数据。
 */
public class InMemoryFileStore implements FileStore {

    private final Map<Long, UploadedFile> filesById = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public long save(UploadedFile file) {
        long id = nextId.getAndIncrement();
        UploadedFile saved = new UploadedFile(id, file.conversationId(), file.turnId(), file.fileName(),
                file.contentType(), file.sizeBytes(), file.kind(), file.parsedText(), file.rawBytes(),
                file.createdAtMillis());
        filesById.put(id, saved);
        return id;
    }

    @Override
    public Optional<UploadedFile> findById(long id) {
        return Optional.ofNullable(filesById.get(id));
    }

    @Override
    public List<UploadedFile> findByConversationId(String conversationId) {
        return filesById.values().stream()
                .filter(file -> file.conversationId().equals(conversationId))
                .sorted(Comparator.comparing(UploadedFile::id))
                .toList();
    }

    @Override
    public void updateParsedText(long id, String parsedText) {
        filesById.computeIfPresent(id, (key, existing) -> new UploadedFile(existing.id(), existing.conversationId(),
                existing.turnId(), existing.fileName(), existing.contentType(), existing.sizeBytes(),
                existing.kind(), parsedText, existing.rawBytes(), existing.createdAtMillis()));
    }
}
