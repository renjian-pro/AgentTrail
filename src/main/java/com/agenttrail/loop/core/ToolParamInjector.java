package com.agenttrail.loop.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Iterator;
import java.util.Map;

/**
 * 把系统级参数强制注入到工具调用参数里（踩坑点 #59 的执行侧）。
 *
 * <p>动态参数分两条通道：
 * <ul>
 *   <li><b>模型可见通道</b>：进 prompt，模型自己决定怎么用
 *   <li><b>模型不可见通道</b>（本类负责）：userId、租户 id 这类系统级参数，
 *       模型从头到尾看不见，由 Runtime 在工具执行前直接写进参数里
 * </ul>
 *
 * <p>为什么不能走提示词让模型自己填：模型会漏填、会填错，更要命的是可以被用户诱导填成
 * 别人的 id——那就是实打实的越权。凡是与安全边界相关的参数，都必须有一层不依赖模型行为的
 * 代码强制。
 *
 * <p>注入按目标工具自己声明的 inputSchema 做白名单过滤：不是每个工具都需要 userId，
 * 无差别注入会给不相干的工具塞进它不认识的字段。schema 缺失或解析不了时**一个都不注入**——
 * 宁可少注入（工具自己会因为缺参数报错，可发现）也不能乱注入（污染参数，难排查）。
 */
class ToolParamInjector {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** JSON Schema 里声明字段的位置。 */
    private static final String SCHEMA_PROPERTIES = "properties";

    private final Map<String, Object> systemParams;

    ToolParamInjector(Map<String, Object> systemParams) {
        this.systemParams = systemParams;
    }

    /**
     * 把该工具声明过的系统级参数写进参数 JSON，已有同名字段一律覆盖。
     *
     * @param arguments  模型给出的、已经过合法性兜底的参数 JSON
     * @param definition 目标工具的定义，用其 inputSchema 做白名单
     * @return 注入后的参数 JSON；无可注入项时原样返回
     */
    String inject(String arguments, ToolDefinition definition) {
        if (systemParams.isEmpty()) {
            return arguments;
        }
        try {
            JsonNode declaredProperties = declaredPropertiesOf(definition);
            if (declaredProperties == null) {
                return arguments;
            }
            ObjectNode merged = (ObjectNode) JSON.readTree(arguments);
            boolean injectedAnything = false;
            for (Map.Entry<String, Object> param : systemParams.entrySet()) {
                if (declaredProperties.has(param.getKey())) {
                    merged.set(param.getKey(), JSON.valueToTree(param.getValue()));
                    injectedAnything = true;
                }
            }
            return injectedAnything ? JSON.writeValueAsString(merged) : arguments;
        } catch (Exception unusable) {
            // 参数或 schema 解析不了：保守放行原参数，工具侧会因为缺字段报错，比静默注错值好排查
            return arguments;
        }
    }

    private JsonNode declaredPropertiesOf(ToolDefinition definition) throws Exception {
        String schema = definition.inputSchema();
        if (schema == null || schema.isBlank()) {
            return null;
        }
        JsonNode properties = JSON.readTree(schema).get(SCHEMA_PROPERTIES);
        return (properties != null && properties.isObject() && hasAnyField(properties)) ? properties : null;
    }

    private boolean hasAnyField(JsonNode node) {
        Iterator<String> fieldNames = node.fieldNames();
        return fieldNames.hasNext();
    }
}
