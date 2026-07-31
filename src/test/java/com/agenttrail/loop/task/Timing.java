package com.agenttrail.loop.task;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** 真实 Redis 场景下等待异步效果生效的小工具，供本包下需要跑 Testcontainers 的用例共用。 */
final class Timing {

    private Timing() {
    }

    /** 轮询直到条件满足或超时；超时直接让测试失败，而不是留一个静默通过的假阳性。 */
    static void waitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("条件在 " + timeout + " 内始终没有满足");
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
