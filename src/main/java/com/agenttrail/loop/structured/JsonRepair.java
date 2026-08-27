package com.agenttrail.loop.structured;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * 结构化输出的 JSON 自动修复兜底（issue #18）。
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
     *
     * <p>同样地，模型在字符串值里直接写英文双引号（"丑角" 这种）会让 JSON 整个断掉——
     * 状态机看到第一个未转义引号会按"字符串结束"处理，后续 token 全部错位。
     * 启发式规则：字符串内出现未转义 {@code "} 时，跳过空白看下一字符——是 {@code ,}/ {@code }}/
     * {@code ]}/ {@code :} 才视为字符串结束，否则视为字符串内容里的引号，替换为左中文引号
     * （避免破坏 JSON 结构；对中文场景可读性也更好）。这种启发式对真实英文引号包裹的
     * 短句会误转成中文引号，但 PPT 大纲这种以中文为主的场景可接受。
     *
     * <p>连续的成对裸引号（"丑角"）按出现顺序交替替换为左右中文引号（"丑角"），
     * 比统一替换为左引号在视觉上更自然。
     */
    private static String normalizeQuotes(String text) {
        String withStraightQuotes = text.replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'');

        StringBuilder rewritten = new StringBuilder(withStraightQuotes.length());
        boolean inString = false;
        boolean escaped = false;
        boolean nextUnescapedIsOpen = true;
        for (int i = 0; i < withStraightQuotes.length(); i++) {
            char c = withStraightQuotes.charAt(i);
            if (inString) {
                if (escaped) {
                    rewritten.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    rewritten.append(c);
                    escaped = true;
                } else if (c == '"') {
                    if (looksLikeStringEnd(withStraightQuotes, i + 1)) {
                        inString = false;
                        rewritten.append(c);
                    } else {
                        // 字符串内未转义的引号：按出现顺序交替替换为左右中文引号
                        rewritten.append(nextUnescapedIsOpen ? '“' : '”');
                        nextUnescapedIsOpen = !nextUnescapedIsOpen;
                    }
                } else {
                    rewritten.append(c);
                }
            } else if (c == '"') {
                inString = true;
                nextUnescapedIsOpen = true;
                rewritten.append(c);
            } else if (c == '\'') {
                rewritten.append('"');
            } else {
                rewritten.append(c);
            }
        }
        return rewritten.toString();
    }

    /**
     * 字符串内出现未转义引号时，判断这是否就是字符串结束。
     * 跳过空白后下一字符是 {@code ,}/ {@code }}/ {@code ]}/ {@code :}/ EOF 才算。
     */
    private static boolean looksLikeStringEnd(String text, int fromIdx) {
        int i = fromIdx;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) {
            return true;
        }
        char next = text.charAt(i);
        return next == ',' || next == '}' || next == ']' || next == ':';
    }

    private static String wrapAsPlainContent(String rawOutput) {
        try {
            return JSON.writeValueAsString(java.util.Map.of("content", rawOutput));
        } catch (Exception neverHappensForAString) {
            return "{\"content\":\"\"}";
        }
    }
}
