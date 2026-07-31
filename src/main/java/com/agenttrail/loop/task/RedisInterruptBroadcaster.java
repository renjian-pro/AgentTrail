package com.agenttrail.loop.task;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.function.Consumer;

/**
 * {@link InterruptBroadcaster} 的 Redis Pub/Sub 实现。一个进程订阅一次，其余实例发布的
 * conversationId 都会广播到所有订阅者（包括发布者自己）——这是 Pub/Sub 天生的语义，
 * 不需要额外判断"是不是发给自己的"，反正没有本地任务的实例收到了也只是一次无效查找。
 */
public final class RedisInterruptBroadcaster implements InterruptBroadcaster, AutoCloseable {

    private static final String CHANNEL = "agenttrail:task-interrupt";

    private final RTopic topic;
    private volatile int listenerId = -1;

    public RedisInterruptBroadcaster(RedissonClient redisson) {
        this.topic = redisson.getTopic(CHANNEL, StringCodec.INSTANCE);
    }

    @Override
    public void broadcastStop(String conversationId) {
        topic.publish(conversationId);
    }

    @Override
    public void onInterruptReceived(Consumer<String> handler) {
        this.listenerId = topic.addListener(String.class, (channel, conversationId) -> handler.accept(conversationId));
    }

    /** 应用关闭时取消订阅，避免连接释放前残留一个不会再被处理的监听器。 */
    @Override
    public void close() {
        if (listenerId != -1) {
            topic.removeListener(listenerId);
        }
    }
}
