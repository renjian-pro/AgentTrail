package com.agenttrail.capability.ppt;

/**
 * PPT 生成状态机的 Strategy 接口（issue #24）——一个状态一个实现类，{@link PptGenerationService}
 * 按 {@link #handledState()} 把 Spring 自动收集的全部实现装配成一个统一的分发表
 * （{@code Map<PptState, PptGenerationStrategy>}），不是散落的 if-else 链。
 *
 * <p>{@link #execute(PptGenerationContext)} 必须在真正完成这个状态该做的全部副作用之后才返回
 * ——{@link PptGenerationService} 只在这个方法正常返回后才会把状态推进并落库，
 * 抛异常则状态原地不动（下次重跑还是它）。这是"状态转移必须发生在副作用真正完成之后"这条
 * 硬约束的落地方式：只要每个实现类不在方法中途"提前"做任何会被下游误当成已完成的持久化动作，
 * 这条约束就自动成立，不需要每个实现类各自小心翼翼地维护。
 */
public interface PptGenerationStrategy {

    PptState handledState();

    PptGenerationContext execute(PptGenerationContext context);

    /**
     * 默认适配旧 Strategy；需要中断 HTTP/Future/子进程的阶段可覆盖这个入口并传播 token。
     * 编排器统一调用该重载，避免每个阶段自行判断数据库取消标记。
     */
    default PptGenerationContext execute(PptGenerationContext context, PptCancellationToken cancellationToken) {
        cancellationToken.throwIfCancellationRequested();
        PptGenerationContext result = execute(context);
        cancellationToken.throwIfCancellationRequested();
        return result;
    }

    /**
     * 带任务绑定进度记录器的内部入口。只有存在阶段内进度的 Strategy 需要覆盖；其他阶段继续使用
     * 两参数实现，避免把 taskId 和 Store 暴露给每一个 Strategy。
     */
    default PptGenerationContext execute(PptGenerationContext context, PptCancellationToken cancellationToken,
            ProgressReporter progressReporter) {
        return execute(context, cancellationToken);
    }

    @FunctionalInterface
    interface ProgressReporter {
        void report(PptState stage, String message, String warningCode);

        static ProgressReporter noop() {
            return (stage, message, warningCode) -> { };
        }
    }
}
