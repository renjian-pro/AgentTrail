package com.agenttrail.loop.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具入参的只读视图。
 *
 * <p>模型给的参数只是"大概率符合 schema"，不是"一定符合"：类型写错（数字给成字符串）、
 * 字段漏传、多传一个不存在的字段都很常见。所以取值一律走带默认值的读取器，
 * 而不是直接 {@code node.get(x).asInt()} 然后等着 NPE——工具因为一个可选参数崩掉，
 * 对模型来说是一条没法自我纠正的错误。
 */
public final class ToolArguments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonNode node;

    private ToolArguments(JsonNode node) {
        this.node = node;
    }

	public static ToolArguments parse(String toolInput) throws JsonProcessingException {
        if (toolInput == null || toolInput.isBlank()) {
            return new ToolArguments(JSON.createObjectNode());
        }
        JsonNode parsed = JSON.readTree(toolInput);
        return new ToolArguments(parsed.isObject() ? parsed : JSON.createObjectNode());
    }

    /** 字符串取值；字段缺失或为 null 时返回 null。数字/布尔也按文本读出来，模型经常传错类型。 */
	public String text(String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.textValue() : value.asText();
    }

	public Integer integer(String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.intValue();
        }
        try {
            return Integer.valueOf(value.asText().trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

	public Long longValue(String field) {
        Integer asInteger = integer(field);
        return asInteger == null ? null : asInteger.longValue();
    }

    /** 布尔标志，缺失时按 false——所有带副作用的开关（覆盖、批量替换）都必须是显式打开的。 */
	public boolean flag(String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
		return value.isBoolean() ? value.booleanValue() : Boolean.parseBoolean(value.asText().trim());
	}

	/** 字符串数组入参；字段缺失、不是数组或数组为空时统一返回空列表。 */
	public List<String> textList(String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isArray()) {
			return List.of();
		}
		List<String> items = new ArrayList<>();
		for (JsonNode element : value) {
			String text = element.isTextual() ? element.textValue() : element.asText();
			if (text != null && !text.isBlank()) {
				items.add(text.trim());
			}
		}
		return List.copyOf(items);
	}
}
