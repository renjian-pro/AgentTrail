package com.agenttrail.capability.ppt.support;

import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptState;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link com.agenttrail.capability.ppt.PptGenerationService} 编排语义的测试替身——只记录"被调用了
 * 第几次"到一个共享日志里，不做任何真实副作用（不调模型、不起子进程）。可选地在被调用的前
 * {@code failFirstNCalls} 次直接抛异常，用来确定性地模拟"这个状态先失败几次，重跑之后才成功"，
 * 验证断点续传是按状态粒度重跑这一个状态、而不是从 INIT 重新来过。
 */
public class RecordingPptGenerationStrategy implements PptGenerationStrategy {

    private final PptState state;
    private final List<String> executionLog;
    private final AtomicInteger callCount = new AtomicInteger();
    private final int failFirstNCalls;

    public RecordingPptGenerationStrategy(PptState state, List<String> executionLog) {
        this(state, executionLog, 0);
    }

    public RecordingPptGenerationStrategy(PptState state, List<String> executionLog, int failFirstNCalls) {
        this.state = state;
        this.executionLog = executionLog;
        this.failFirstNCalls = failFirstNCalls;
    }

    @Override
    public PptState handledState() {
        return state;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        int thisCall = callCount.incrementAndGet();
        executionLog.add(state.name() + "#" + thisCall);
        if (thisCall <= failFirstNCalls) {
            throw new PptGenerationException(state + " 第 " + thisCall + " 次执行故意失败（测试用）");
        }
        return context;
    }

    public int callCount() {
        return callCount.get();
    }
}
