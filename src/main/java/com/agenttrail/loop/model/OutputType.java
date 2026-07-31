package com.agenttrail.loop.model;

import org.springframework.core.ParameterizedTypeReference;

/**
 * 结构化输出的目标类型声明（issue #18）。挂在 {@link RunnableParams#outputType()} 上，
 * 循环据此把 JSON Schema 格式指令注入给模型，并在收尾时对不合法的 JSON 输出做一次自动修复。
 *
 * <p>只支持单对象类型，不像 agentx-core 的同名类那样额外提供 {@code listOf(Class)}——
 * 目前没有调用方需要"输出一个列表"，用不到的抽象先不引入，真有需求时再加不迟。
 */
public final class OutputType {

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
}
