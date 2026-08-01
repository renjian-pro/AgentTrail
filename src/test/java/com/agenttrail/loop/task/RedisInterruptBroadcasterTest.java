package com.agenttrail.loop.task;

import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RedisInterruptBroadcasterTest {

    @Test
    void usesTheSharedChannelAndPublishesConversationIds() {
        TopicProbe topic = new TopicProbe();

        RedisInterruptBroadcaster broadcaster = new RedisInterruptBroadcaster(redissonReturning(topic));
        broadcaster.broadcastStop("conv-1");

        assertThat(topic.requestedName()).isEqualTo("agenttrail:task-interrupt");
        assertThat(topic.requestedCodec()).isSameAs(StringCodec.INSTANCE);
        assertThat(topic.publishedMessages()).containsExactly("conv-1");
    }

    @Test
    void forwardsMessagesToTheRegisteredHandlerAndRemovesTheListenerOnClose() {
        TopicProbe topic = new TopicProbe();
        AtomicReference<String> received = new AtomicReference<>();
        RedisInterruptBroadcaster broadcaster = new RedisInterruptBroadcaster(redissonReturning(topic));

        broadcaster.onInterruptReceived(received::set);
        topic.registeredListener().get().onMessage(null, "conv-2");
        broadcaster.close();

        assertThat(received).hasValue("conv-2");
        assertThat(topic.removedListenerIds()).containsExactly(42);
    }

    @Test
    void closingBeforeAHandlerWasRegisteredDoesNotRemoveAnUnknownListener() {
        TopicProbe topic = new TopicProbe();

        new RedisInterruptBroadcaster(redissonReturning(topic)).close();

        assertThat(topic.removedListenerIds()).isEmpty();
    }

    private static RedissonClient redissonReturning(TopicProbe topic) {
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> {
                    if ("getTopic".equals(method.getName())) {
                        topic.recordTopicRequest((String) args[0], args[1]);
                        return topic.proxy();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == int.class || returnType == short.class || returnType == byte.class) {
            return 0;
        }
        if (returnType == double.class || returnType == float.class) {
            return 0.0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class TopicProbe {
        private final List<Object> publishedMessages = new ArrayList<>();
        private final List<Integer> removedListenerIds = new ArrayList<>();
        private final AtomicReference<MessageListener<String>> registeredListener = new AtomicReference<>();
        private String requestedName;
        private Object requestedCodec;

        private RTopic proxy() {
            return (RTopic) Proxy.newProxyInstance(
                    RTopic.class.getClassLoader(),
                    new Class<?>[]{RTopic.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "publish" -> {
                            publishedMessages.add(args[0]);
                            yield 1L;
                        }
                        case "addListener" -> {
                            @SuppressWarnings("unchecked")
                            MessageListener<String> listener = (MessageListener<String>) args[1];
                            registeredListener.set(listener);
                            yield 42;
                        }
                        case "removeListener" -> {
                            Integer[] ids = (Integer[]) args[0];
                            removedListenerIds.addAll(List.of(ids));
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private void recordTopicRequest(String requestedName, Object requestedCodec) {
            this.requestedName = requestedName;
            this.requestedCodec = requestedCodec;
        }

        private String requestedName() {
            return requestedName;
        }

        private Object requestedCodec() {
            return requestedCodec;
        }

        private List<Object> publishedMessages() {
            return publishedMessages;
        }

        private AtomicReference<MessageListener<String>> registeredListener() {
            return registeredListener;
        }

        private List<Integer> removedListenerIds() {
            return removedListenerIds;
        }
    }
}
