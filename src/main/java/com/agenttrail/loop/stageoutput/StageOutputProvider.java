package com.agenttrail.loop.stageoutput;

/**
 * 阶段输出提供者 SPI（issue #16）。调用方实现这个接口，往循环里插一段额外输出，不需要改动
 * {@code AgentLoopExecutor} 本身——这个接口纯粹是"给什么数据、什么时候被调用"，不知道自己会
 * 被用在什么业务场景（欢迎语、引用链接、推荐问题都只是使用示例，不是接口的一部分）。
 *
 * <p>这是"进循环里插一段东西"这件事的通用解法，替代继续在 {@link
 * com.agenttrail.loop.core.ToolCallExecutor} 这类共享基础设施里按工具名加 {@code if} 特例
 * （TodoWrite 的进度事件目前就是这么接的，见该类的 {@code emitTodoProgressIfApplicable}——
 * 这个机制补上之后，那处特例是下一个该收编成 provider 的候选）。
 */
public interface StageOutputProvider {

    /** 阶段名称，用来标识 {@code AgentStreamEvent.StageOutput} 事件的来源，比如 "reference"。 */
    String name();

    /** 这个 provider 该在哪个生命周期钩子点被调用。 */
    StageTiming timing();

    /**
     * 产生阶段输出。
     *
     * @param context 阶段上下文
     * @return 阶段输出数据；返回 null 表示这次没有输出，不会发 {@code StageOutput} 事件
     */
    Object produce(StageContext context);
}
