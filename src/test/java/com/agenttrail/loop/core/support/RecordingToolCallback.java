package com.agenttrail.loop.core.support;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@link ToolCallback} 的测试替身：记录每次被调用时收到的**原始 JSON 参数字符串**。
 *
 * <p>记原始字符串而不是解析后的对象，是因为分片重组、非法参数降级这些机制的正确性
 * 只能在字符串层面断言——解析成对象之后，"参数是不是被拼对了"这个信息就丢了。
 */
public class RecordingToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> behaviour;
    private final List<String> recordedArguments = new ArrayList<>();

    /** 固定返回同一个结果的工具。 */
    public RecordingToolCallback(String name, String description, String result) {
        this(name, description, arguments -> result);
    }

    /** 返回值依赖入参的工具，用于验证不同调用确实拿到了各自的参数。 */
    public RecordingToolCallback(String name, String description, Function<String, String> behaviour) {
        this(name, description, "{}", behaviour);
    }

    /** 声明了入参 schema 的工具，用于验证系统级参数注入时的白名单过滤。 */
    public RecordingToolCallback(String name, String description, String inputSchema, String result) {
        this(name, description, inputSchema, arguments -> result);
    }

    private RecordingToolCallback(String name, String description, String inputSchema,
                                  Function<String, String> behaviour) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        this.behaviour = behaviour;
    }

    /** 构造一个只声明了这些字段的 JSON Schema，省得测试里手写一长串。 */
    public static String schemaWith(String... propertyNames) {
        StringBuilder properties = new StringBuilder();
        for (String propertyName : propertyNames) {
            if (!properties.isEmpty()) {
                properties.append(',');
            }
            properties.append('"').append(propertyName).append("\":{\"type\":\"string\"}");
        }
        return "{\"type\":\"object\",\"properties\":{" + properties + "}}";
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        recordedArguments.add(toolInput);
        return behaviour.apply(toolInput);
    }

    /** 按调用顺序记录下来的原始参数字符串。 */
    public List<String> recordedArguments() {
        return recordedArguments;
    }
}
