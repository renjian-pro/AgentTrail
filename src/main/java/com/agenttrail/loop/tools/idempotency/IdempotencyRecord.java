package com.agenttrail.loop.tools.idempotency;

import java.time.Instant;

/**
 * 一个幂等键在存储里的状态记录。
 *
 * <p>只有两个状态，对应"副作用有没有确定发生"这一件事：
 * <ul>
 *   <li>{@link Status#IN_FLIGHT} —— 有人抢到了这个键、正在执行，副作用**可能**已经发生了一半。
 *       这个状态带租约（见 {@link IdempotencyStore#claim}）：执行者进程崩了、或者恰好在
 *       {@code TOOL_EXECUTION} 安全点被中断（踩坑点 #34），记录会永远停在 IN_FLIGHT，
 *       只能靠租约过期来解除，否则这个键就被永久毒化了。</li>
 *   <li>{@link Status#COMPLETED} —— 执行成功结束，{@link #result} 是首次执行的返回值。
 *       后续同键调用直接回放它，不再执行委托工具。</li>
 * </ul>
 *
 * <p>没有 FAILED 状态是刻意的：执行抛异常时记录被删除（见
 * {@link IdempotentToolCallback}），让重试能真正重新执行。代价是"至少一次"语义——
 * 详见 {@link IdempotentToolCallback} 类注释里对失败语义的说明。
 *
 * @param key       完整幂等键（已带工具名前缀）
 * @param status    当前状态
 * @param result    首次执行的返回值，仅 COMPLETED 时非 null
 * @param updatedAt 状态最后一次变更的时刻；IN_FLIGHT 时用于算租约是否过期，COMPLETED 时用于算 TTL
 */
public record IdempotencyRecord(String key, Status status, String result, Instant updatedAt) {

    public enum Status {
        /** 已被某个执行者占位，尚未完成。 */
        IN_FLIGHT,
        /** 已成功执行完成，结果可回放。 */
        COMPLETED
    }

    public static IdempotencyRecord inFlight(String key, Instant claimedAt) {
        return new IdempotencyRecord(key, Status.IN_FLIGHT, null, claimedAt);
    }

    public static IdempotencyRecord completed(String key, String result, Instant completedAt) {
        return new IdempotencyRecord(key, Status.COMPLETED, result, completedAt);
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }
}
