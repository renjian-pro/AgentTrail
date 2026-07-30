package com.agenttrail.loop.tools.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 内置的几种 {@link IdempotencyKeyStrategy}，覆盖绝大多数写类工具的取键需求。
 */
public final class IdempotencyKeyStrategies {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IdempotencyKeyStrategies() {
    }

    /**
     * 参数摘要：对参数 JSON 做**规范化**后取 SHA-256。
     *
     * <p>规范化（递归按字段名排序）不是可选项：模型两次生成的 JSON 字段顺序经常不同，
     * 直接对原始字符串取摘要会把 {@code {"a":1,"b":2}} 和 {@code {"b":2,"a":1}} 算成两次不同调用，
     * 去重形同虚设。数组顺序**不排**——数组顺序在业务上通常是有意义的。
     *
     * <p>参数不是合法 JSON 时（{@code ToolCallExecutor} 已经把非法参数降级成 {@code {}}，
     * 这里只是兜底）退化成对原始字符串取摘要，行为仍然确定。
     */
    public static IdempotencyKeyStrategy argumentDigest() {
        return (toolInput, definition) -> Optional.of(sha256(canonicalize(toolInput)));
    }

    /**
     * 显式幂等 token：从参数里读指定字段作为幂等键。
     *
     * <p>字段缺失、为空、或者参数不是合法 JSON 时返回空（装饰器会透传执行）——
     * 这是**故意**不抛异常的：见 {@link IdempotencyKeyStrategy} 类注释。
     */
    public static IdempotencyKeyStrategy argumentField(String fieldName) {
        return (toolInput, definition) -> readTextField(toolInput, fieldName);
    }

    /**
     * 显式 token 优先，缺失时退回参数摘要。
     *
     * <p>推荐给"schema 里有 token 字段、但不能保证模型每次都填"的工具：有 token 时语义最准，
     * 没 token 时至少还有一层摘要兜底，不会直接退化成完全不幂等。
     */
    public static IdempotencyKeyStrategy argumentFieldOrDigest(String fieldName) {
        IdempotencyKeyStrategy digest = argumentDigest();
        return (toolInput, definition) -> readTextField(toolInput, fieldName)
                .or(() -> digest.deriveKey(toolInput, definition));
    }

    private static Optional<String> readTextField(String toolInput, String fieldName) {
        if (toolInput == null || toolInput.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(toolInput);
            JsonNode field = root.get(fieldName);
            if (field == null || field.isNull()) {
                return Optional.empty();
            }
            String value = field.asText();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    /** 递归排序对象字段后序列化；解析不了就原样返回，保证任何输入都有确定的摘要输入。 */
    private static String canonicalize(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "";
        }
        try {
            return JSON.writeValueAsString(sortFields(JSON.readTree(toolInput)));
        } catch (Exception malformed) {
            return toolInput.trim();
        }
    }

    private static JsonNode sortFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode sorted = JSON.createObjectNode();
            names.forEach(name -> sorted.set(name, sortFields(object.get(name))));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode mapped = JSON.createArrayNode();
            array.forEach(element -> mapped.add(sortFields(element)));
            return mapped;
        }
        return node;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是 JDK 强制要求实现的算法，走不到这里
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }
}
