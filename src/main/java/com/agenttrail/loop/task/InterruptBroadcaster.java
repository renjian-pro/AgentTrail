package com.agenttrail.loop.task;

import java.util.function.Consumer;

/**
 * 跨实例中断广播：本地找不到要停止的任务时，通过这个接口告诉"其它实例，谁手上有这个会话就
 * 自己停掉它"。{@link AgentTaskManager} 只依赖这个接口，不知道底下是 Redis 还是别的什么
 * （见 {@link RedisTaskLock} 同样的边界划分——本类只管"消息怎么传"，不碰任务本身的取消逻辑）。
 */
public interface InterruptBroadcaster {

    /** 广播"请停止这个会话"——发给所有实例，包括自己；自己应该已经先走过本地快路径了。 */
    void broadcastStop(String conversationId);

    /**
     * 注册"收到广播时要做什么"。持有本地任务表的一方（{@link AgentTaskManager}）在构造时
     * 把自己的本地停止逻辑注册进来，本类不关心 handler 具体做了什么。
     */
    void onInterruptReceived(Consumer<String> handler);
}
