package com.agenttrail.runtime.api;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构化输出的目标类型声明（issue #18）。挂在 {@link RunnableParams#outputType()} 上，
 * 循环据此把 JSON Schema 格式指令注入给模型，并在收尾时对不合法的 JSON 输出做一次自动修复。
 *
 * <p>只支持单对象类型，不像 agentx-core 的同名类那样额外提供 {@code listOf(Class)}——
 * 目前没有调用方需要"输出一个列表"，用不到的抽象先不引入，真有需求时再加不迟。
 */
public final class OutputType {

    /**
     * 格式指令只取决于 {@code type} 本身，跟发起方是谁、第几次调用都无关——每次真人发一轮对话就
     * 反射生成一遍 schema 没有意义。按 Class 缓存，跨所有 {@code OutputType.of(sameClass)} 实例共享，
     * 而不是缓存在实例字段上（调用方通常每次请求都 new 一个新的 {@code OutputType}，实例级缓存起不到作用）。
     */
    private static final Map<Class<?>, String> FORMAT_CACHE = new ConcurrentHashMap<>();

    private final Class<?> type;

    private OutputType(Class<?> type) {
        this.type = type;
    }

    public static OutputType of(Class<?> type) {
        return new OutputType(type);
    }

    public Class<?> type() {
        return type;
    }

    /** 供 {@code BeanOutputConverter} 使用，取格式指令时需要这个包装。 */
    public ParameterizedTypeReference<?> toTypeReference() {
        return ParameterizedTypeReference.forType(type);
    }

    /** 这个类型对应的 JSON Schema 格式指令文本，跨调用缓存。 */
    public String formatInstruction() {
        return FORMAT_CACHE.computeIfAbsent(type,
                ignored -> new BeanOutputConverter<>(toTypeReference()).getFormat());
    }
}
