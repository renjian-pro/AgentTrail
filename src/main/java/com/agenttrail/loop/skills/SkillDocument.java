package com.agenttrail.loop.skills;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份 SKILL.md 拆开之后的两半：头部 YAML frontmatter 的键值对，和剩下的正文。
 *
 * <p>不引 YAML 依赖，只按行扫描 {@code key: value}。技能文件的 frontmatter 事实上只有
 * name/description 这几个平铺的字符串字段，为它拖进一个完整 YAML 解析器不划算；
 * 真出现嵌套结构也不该被当成"模型选技能的依据"塞进工具描述里。
 *
 * <p>两处和参考实现不同，都是踩过的读法问题：
 * <ul>
 *   <li>结束分隔符按**整行等于 {@code ---}** 判定，不是 {@code indexOf("---", 3)}。
 *       后者遇到 {@code description: 先做 A --- 再做 B} 会从值中间切断，
 *       前半截当 frontmatter、后半截当正文，两边都是坏的。
 *   <li>用 {@link LinkedHashMap} 保持书写顺序。frontmatter 最终要渲染进工具描述，
 *       而工具描述是提示词前缀的一部分——用 HashMap 的话同一份技能文件在不同 JVM 里
 *       可能渲染出不同顺序的前缀，prompt 缓存直接失效。
 * </ul>
 */
public record SkillDocument(Map<String, String> frontmatter, String body) {

    private static final String DELIMITER = "---";

    public SkillDocument {
        // 防御性拷贝仍然用 LinkedHashMap —— Map.copyOf 会丢掉书写顺序，那正是这里要保住的东西
        frontmatter = Collections.unmodifiableMap(
                new LinkedHashMap<>(frontmatter == null ? Map.of() : frontmatter));
        body = body == null ? "" : body;
    }

    /** @return frontmatter 里该 key 的值，没有则返回 {@code null} */
    public String value(String key) {
        return frontmatter.get(key);
    }

    /**
     * 解析一份 Markdown。没有 frontmatter、frontmatter 开了头没闭合、内容为空——
     * 这三种都当"整篇是正文"处理，不抛异常：技能文件是人手写的，写坏一个不该让整轮装配失败。
     */
    public static SkillDocument parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new SkillDocument(new LinkedHashMap<>(), "");
        }

        String[] lines = markdown.split("\r?\n", -1);
        int first = firstNonBlankLine(lines);
        if (first < 0 || !DELIMITER.equals(lines[first].trim())) {
            return new SkillDocument(new LinkedHashMap<>(), markdown.strip());
        }

        int closing = closingDelimiter(lines, first + 1);
        if (closing < 0) {
            // 只有开头那行 ---，没有闭合：把它当普通正文，别把整篇吞成元数据
            return new SkillDocument(new LinkedHashMap<>(), markdown.strip());
        }

        Map<String, String> frontmatter = new LinkedHashMap<>();
        for (int i = first + 1; i < closing; i++) {
            parseEntry(lines[i], frontmatter);
        }
        return new SkillDocument(frontmatter, joinFrom(lines, closing + 1));
    }

    private static int firstNonBlankLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static int closingDelimiter(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private static void parseEntry(String line, Map<String, String> into) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return;
        }
        into.put(trimmed.substring(0, colon).trim(), unquote(trimmed.substring(colon + 1).trim()));
    }

    /** 只脱掉成对的引号；单边引号是内容的一部分，脱了反而改变原意。 */
    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String joinFrom(String[] lines, int from) {
        StringBuilder body = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1) {
                body.append('\n');
            }
        }
        return body.toString().strip();
    }
}
