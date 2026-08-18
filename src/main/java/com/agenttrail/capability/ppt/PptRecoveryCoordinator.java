package com.agenttrail.capability.ppt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PPT 任务的有界恢复扫描器。它只负责从持久化状态挑出可恢复 taskId 并重新入队，
 * 不在查询接口里隐式驱动执行；真正的租约获取、revision claim 和失败分类仍由
 * {@link PptGenerationService#run(long)} 统一处理。
 */
public final class PptRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PptRecoveryCoordinator.class);

    private final PptTaskStore taskStore;
    private final PptGenerationService generationService;
    private final Executor executor;
    private final int batchSize;
    private final long jitterMillis;

    public PptRecoveryCoordinator(PptTaskStore taskStore, PptGenerationService generationService,
            Executor executor, int batchSize, long jitterMillis) {
        if (batchSize < 1 || jitterMillis < 0) {
            throw new IllegalArgumentException("PPT recovery batch and jitter must be non-negative/positive");
        }
        this.taskStore = taskStore;
        this.generationService = generationService;
        this.executor = executor;
        this.batchSize = batchSize;
        this.jitterMillis = jitterMillis;
    }

    /** 应用启动时先做一轮恢复，避免重启后的 RUNNING/QUEUED 任务一直等到定时周期。 */
    @PostConstruct
    public void recoverOnStartup() {
        recoverOnce();
    }

    /**
     * 定时扫描入口；批量上限和轻微随机抖动共同限制启动/重启洪峰。
     * {@code RETRY_WAIT} 只有到 nextRetryAt 才会被 store 返回，FAILED/WAITING_INPUT 会被跳过。
     */
    @Scheduled(fixedDelayString = "${agenttrail.ppt.recovery.interval-ms:30000}",
            initialDelayString = "${agenttrail.ppt.recovery.initial-delay-ms:5000}")
    public int recoverOnce() {
        List<Long> candidates = new ArrayList<>(
                taskStore.recoverableTaskIds(System.currentTimeMillis(), batchSize));
        Collections.shuffle(candidates);
        for (long taskId : candidates) {
            if (jitterMillis > 0) {
                long delay = ThreadLocalRandom.current().nextLong(jitterMillis + 1);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            try {
                executor.execute(() -> {
                    try {
                        generationService.run(taskId);
                    } catch (RuntimeException recoveryFailed) {
                        log.warn("PPT 任务 {} 恢复执行失败，等待下一轮扫描: {}", taskId,
                                recoveryFailed.getMessage());
                    }
                });
            } catch (RuntimeException rejected) {
                log.warn("PPT 任务 {} 恢复入队失败，等待下一轮扫描: {}", taskId, rejected.getMessage());
            }
        }
        return candidates.size();
    }
}
