package com.agenttrail.loop.stageoutput;

import com.agenttrail.loop.model.AgentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 按 {@link StageTiming} 分组管理 {@link StageOutputProvider}，在循环的固定生命周期点
 * 依次调用已注册的 provider，通过 emitter 发 {@code AgentStreamEvent.StageOutput} 事件。
 *
 * <p>没有注册任何 provider 时（{@link #EMPTY}）三个钩子方法都是空操作——不遍历空 map、
 * 不分配任何中间对象，调用方（{@code AgentLoopExecutor}）不需要另外判断"要不要走这段逻辑"，
 * 直接无条件调用钩子方法即可。
 */
public class StageOutputManager {

    private static final Logger log = LoggerFactory.getLogger(StageOutputManager.class);

    /** 没有注册任何 provider 时用这个单例，三个钩子方法都是空操作。 */
    public static final StageOutputManager EMPTY = new StageOutputManager(List.of());

    private final Map<StageTiming, List<StageOutputProvider>> providersByTiming;

    public StageOutputManager(List<StageOutputProvider> providers) {
        this.providersByTiming = (providers == null || providers.isEmpty())
                ? Collections.emptyMap()
                : providers.stream().collect(Collectors.groupingBy(StageOutputProvider::timing));
    }

    public void afterStart(StageContext context, Consumer<AgentStreamEvent> emitter) {
        invoke(StageTiming.AFTER_START, context, emitter);
    }

    public void afterToolEnd(StageContext context, Consumer<AgentStreamEvent> emitter) {
        invoke(StageTiming.AFTER_TOOL_END, context, emitter);
    }

    public void beforeComplete(StageContext context, Consumer<AgentStreamEvent> emitter) {
        invoke(StageTiming.BEFORE_COMPLETE, context, emitter);
    }

    /**
     * 一个 provider 出异常不该拖垮其它 provider、更不该拖垮整个循环——记日志、跳过，
     * 剩下的 provider 照常跑，就像工具调用失败不会中断整轮对话一样。
     */
    private void invoke(StageTiming timing, StageContext context, Consumer<AgentStreamEvent> emitter) {
        for (StageOutputProvider provider : providersByTiming.getOrDefault(timing, List.of())) {
            try {
                Object output = provider.produce(context);
                if (output != null) {
                    emitter.accept(new AgentStreamEvent.StageOutput(provider.name(), output));
                }
            } catch (RuntimeException failure) {
                log.error("StageOutputProvider '{}' 执行失败: {}", provider.name(), failure.getMessage(), failure);
            }
        }
    }
}
