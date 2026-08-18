package com.agenttrail.capability.ppt;

/**
 * 阶段内取消协议。数据库的 CANCEL_REQUESTED 是跨实例事实，这个 token 是本地执行单元的快速通知；
 * 两者都要检查，才能在模型/渲染返回后阻止迟到 checkpoint。
 */
@FunctionalInterface
public interface PptCancellationToken {

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new PptCancellationException();
        }
    }

    static PptCancellationToken never() {
        return () -> false;
    }
}
