package com.agenttrail.capability.ppt;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内的任务取消通知。它不替代数据库标记：进程重启后 token 会丢失，恢复 worker 仍先读取
 * 持久化的 CANCEL_REQUESTED 再决定是否执行。
 */
public final class PptCancellationRegistry {
    private final ConcurrentHashMap<Long, Source> sources = new ConcurrentHashMap<>();

    public PptCancellationToken tokenFor(long taskId) {
        return sources.computeIfAbsent(taskId, ignored -> new Source()).token();
    }

    public void cancel(long taskId) {
        sources.computeIfAbsent(taskId, ignored -> new Source()).cancel();
    }

    public void remove(long taskId) {
        sources.remove(taskId);
    }

    private static final class Source {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        PptCancellationToken token() {
            return cancelled::get;
        }

        void cancel() {
            cancelled.set(true);
        }
    }
}
