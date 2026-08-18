package com.agenttrail.loop.pause;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 只用于向客户端展示工具参数；原始参数仍保存在 PauseState 中供恢复执行。 */
public final class ToolArgumentSanitizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "token", "authorization", "apikey", "apitoken",
            "accesskey", "privatekey", "credential", "credentials");

    private ToolArgumentSanitizer() {
    }

    public static String sanitize(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        try {
            JsonNode root = JSON.readTree(arguments);
            if (root == null || (!root.isObject() && !root.isArray())) {
                return "{\"redacted\":\"arguments unavailable\"}";
            }
            redact(root);
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return "{\"redacted\":\"arguments unavailable\"}";
        }
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    object.put(field.getKey(), "***");
                } else {
                    redact(field.getValue());
                }
            }
            return;
        }
        if (node instanceof ArrayNode array) {
            array.forEach(ToolArgumentSanitizer::redact);
        }
    }

    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEYS.contains(normalized);
    }
}
