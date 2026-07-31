package com.agenttrail.loop.tools.search;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个延迟工具的检索索引：名称按 camelCase/snake_case 拆词，描述按词边界拆词，供关键词打分用。
 *
 * <p>中文没有空白词边界，这里不接分词器——退化成子串匹配（见 {@link #score}），
 * 准确率不如真正的分词，但作为关键词兜底已经够用。
 */
record ToolIndexEntry(String name, String description, List<String> nameTokens, List<String> descriptionTokens) {

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    static List<ToolIndexEntry> buildIndex(Map<String, ToolCallback> tools) {
        List<ToolIndexEntry> index = new ArrayList<>();
        for (Map.Entry<String, ToolCallback> entry : tools.entrySet()) {
            String name = entry.getKey();
            String description = Objects.requireNonNullElse(entry.getValue().getToolDefinition().description(), "");
            index.add(new ToolIndexEntry(name, description, tokenizeName(name), tokenize(description)));
        }
        return List.copyOf(index);
    }

    private static List<String> tokenizeName(String name) {
        String withSpaces = CAMEL_BOUNDARY.matcher(name).replaceAll("$1 $2").replace('_', ' ');
        return tokenize(withSpaces);
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : WORD_SPLIT.split(text.toLowerCase())) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** 打分维度由高到低：名称精确匹配 > 名称分词命中 > 名称子串 > 描述分词命中 > 描述子串。 */
    int score(String query, List<String> queryTokens) {
        String lowerQuery = query.toLowerCase();
        String lowerName = name.toLowerCase();
        int score = 0;

        if (name.equalsIgnoreCase(query)) {
            score += 100;
        }
        for (String token : queryTokens) {
            if (nameTokens.contains(token)) {
                score += 50;
            }
        }
        if (lowerName.contains(lowerQuery)) {
            score += 20;
        }
        for (String token : queryTokens) {
            if (descriptionTokens.contains(token)) {
                score += 10;
            }
        }
        if (description.toLowerCase().contains(lowerQuery)) {
            score += 5;
        }
        return score;
    }
}
