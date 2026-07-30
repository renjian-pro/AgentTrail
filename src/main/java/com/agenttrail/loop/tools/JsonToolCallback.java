package com.agenttrail.loop.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.function.Function;

/**
 * 把"解析后的入参 → 结果字符串"的函数包装成一个 {@link ToolCallback}。
 *
 * <p>存在的意义是把三个内置工具共用的样板收口到一处：JSON 解析、异常吞成工具结果、
 * 工具定义的组装。这样每个工具类只需要关心自己的业务语义。
 *
 * <p>为什么异常不往外抛：和 {@code ToolCallExecutor} 已有的约定保持一致——工具层的错误
 * 以"工具结果"的形式喂回模型，模型下一轮能看到错误、有机会改参数重试或换路子；
 * 抛出去则整轮对话直接死掉。区别只在于日志级别：越权访问是安全事件，单独记 warn。
 */
final class JsonToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(JsonToolCallback.class);

    private final ToolDefinition definition;
    private final Function<ToolArguments, String> body;

    JsonToolCallback(String name, String description, String inputSchema,
                     Function<ToolArguments, String> body) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        this.body = body;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        ToolArguments arguments;
        try {
            arguments = ToolArguments.parse(toolInput);
        } catch (JsonProcessingException malformed) {
            // 上游 ToolCallExecutor 已经把非法 JSON 降级成 "{}"，能走到这里说明是别的调用方，
            // 同样按"工具结果"返回，不抛异常
            return "Error: 工具参数不是合法的 JSON 对象：" + malformed.getOriginalMessage();
        }

        try {
            return body.apply(arguments);
        } catch (SandboxViolationException violation) {
            log.warn("工具 {} 触发目录白名单拦截：{}", definition.name(), violation.getMessage());
            return "Error: " + violation.getMessage();
        } catch (RuntimeException failure) {
            log.error("工具 {} 执行失败：{}", definition.name(), failure.getMessage(), failure);
            return "Error: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }
}
