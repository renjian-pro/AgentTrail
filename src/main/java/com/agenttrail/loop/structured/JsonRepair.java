package com.agenttrail.loop.structured;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * 结构化输出的 JSON 自动修复兜底（issue #18）。移植自 agentx-core 的 {@code JsonRepairUtil}——
 * 模型即便被明确要求输出 JSON，也常常夹带 markdown 代码块、尾部逗号、中文引号这类"几乎是 JSON"
 * 的瑕疵，这里按启发式规则逐步修，而不是模型一犯错就直接报错了事。
 *
 * <p>{@link #fixJson} 保证不抛异常、总有返回值——修复链条走到最后仍不是合法 JSON 时，
 * 降级为把原始文本包成 {@code {"content": "..."}}，让调用方总能拿到一个可解析的 JSON 字符串，
 * 不会因为一次输出格式问题就让整轮对话崩掉。
 */
public final class JsonRepair {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern MARKDOWN_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_COMMA_OBJECT = Pattern.compile(",\\s*}");
    private static final Pattern TRAILING_COMMA_ARRAY = Pattern.compile(",\\s*]");
    private static final Pattern UNQUOTED_KEY = Pattern.compile("([{,]\\s*)([a-zA-Z_][a-zA-Z0-9_]*)\\s*:");
    private static final Pattern UNESCAPED_NEWLINE = Pattern.compile("(?<!\\\\)[\\n\\r\\t]");

    private JsonRepair() {
    }

    public static String fixJson(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "{}";
        }

        String fixed = rawOutput.trim();
        fixed = extractFromMarkdownFence(fixed);
        fixed = trimToOutermostBrackets(fixed);
        fixed = normalizeQuotes(fixed);
        fixed = TRAILING_COMMA_OBJECT.matcher(fixed).replaceAll("}");
        fixed = TRAILING_COMMA_ARRAY.matcher(fixed).replaceAll("]");
        fixed = UNQUOTED_KEY.matcher(fixed).replaceAll("$1\"$2\":");
        // 字符串值里裸露的换行/制表符不是合法 JSON——模型很容易忘了转义，直接换成空格
        fixed = UNESCAPED_NEWLINE.matcher(fixed).replaceAll(" ");

        if (isValidJson(fixed)) {
            return fixed;
        }
        return wrapAsPlainContent(rawOutput);
    }

    public static boolean isValidJson(String candidate) {
        try {
            JSON.readTree(candidate);
            return true;
        } catch (Exception notValidJson) {
            return false;
        }
    }

    private static String extractFromMarkdownFence(String text) {
        var matcher = MARKDOWN_FENCE.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : text;
    }

    /** 截掉第一个 {@code {}/[} 之前和最后一个 {@code }/]} 之后的说明性文字。 */
    private static String trimToOutermostBrackets(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        int end = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}' || c == ']') {
                end = i + 1;
                break;
            }
        }
        return (start != -1 && end != -1 && start < end) ? text.substring(start, end) : text;
    }

    /**
     * 中文引号统一替换为英文引号；字符串值*外部*的单引号视为结构性引号一并替换。
     *
     * <p>字符串边界只能靠状态机按字符扫描——正则没法可靠区分"结构位置的单引号"和
     * "字符串内容里的单引号"，后者一旦被误替换，内容就变形了（比如某段代码里的
     * {@code $_SERVER['REQUEST_METHOD']}）。
     */
    private static String normalizeQuotes(String text) {
        String withStraightQuotes = text.replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'');

        StringBuilder rewritten = new StringBuilder(withStraightQuotes.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < withStraightQuotes.length(); i++) {
            char c = withStraightQuotes.charAt(i);
            if (inString) {
                rewritten.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                rewritten.append(c);
            } else if (c == '\'') {
                rewritten.append('"');
            } else {
                rewritten.append(c);
            }
        }
        return rewritten.toString();
    }

    private static String wrapAsPlainContent(String rawOutput) {
        try {
            return JSON.writeValueAsString(java.util.Map.of("content", rawOutput));
        } catch (Exception neverHappensForAString) {
            return "{\"content\":\"\"}";
        }
    }
}
