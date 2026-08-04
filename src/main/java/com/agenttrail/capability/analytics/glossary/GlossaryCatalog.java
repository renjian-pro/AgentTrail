package com.agenttrail.capability.analytics.glossary;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 业务术语只做精确匹配，避免把相似词误改写成 SQL。 */
@Component
public class GlossaryCatalog {
    private final Resource resource;
    private volatile Map<String, GlossaryEntry> entries = Map.of();

    public GlossaryCatalog(@Value("classpath:analytics/glossary.yml") Resource resource) {
        this.resource = resource;
    }

    @PostConstruct
    public void load() {
        try (InputStream input = resource.getInputStream()) {
            LoaderOptions options = new LoaderOptions();
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            this.entries = parse(loaded);
        } catch (Exception failure) {
            throw new IllegalStateException("业务术语字典加载失败", failure);
        }
    }

    public List<GlossaryEntry> all() {
        return entries.values().stream().distinct().toList();
    }

    public List<String> allTerms() {
        return all().stream().map(GlossaryEntry::term).toList();
    }

    public GlossaryEntry lookup(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        return entries.get(normalize(term));
    }

    public String format(String term) {
        GlossaryEntry entry = lookup(term);
        if (entry == null) {
            return "未找到精确匹配的业务术语：" + term
                    + "\n当前已登记的术语：" + String.join("、", allTerms());
        }
        StringBuilder result = new StringBuilder("## ").append(entry.term())
                .append("\n").append(entry.description());
        if (!entry.synonyms().isEmpty()) {
            result.append("\n同义词：").append(String.join("、", entry.synonyms()));
        }
        if (!entry.sqlFragment().isBlank()) {
            result.append("\nSQL 口径：").append(entry.sqlFragment());
        }
        if (!entry.example().isBlank()) {
            result.append("\n示例：").append(entry.example());
        }
        return result.toString();
    }

    private static Map<String, GlossaryEntry> parse(Object loaded) {
        if (!(loaded instanceof Map<?, ?> root)) {
            return Map.of();
        }
        Map<String, GlossaryEntry> result = new LinkedHashMap<>();
        Object terms = root.get("terms");
        if (terms instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String term = text(map.get("term"));
                if (term.isBlank()) {
                    continue;
                }
                List<String> synonyms = strings(map.get("synonyms"));
                GlossaryEntry entry = new GlossaryEntry(term, synonyms,
                        text(map.get("description")), text(map.get("sql_fragment")), text(map.get("example")));
                result.put(normalize(term), entry);
                for (String synonym : synonyms) {
                    result.putIfAbsent(normalize(synonym), entry);
                }
            }
        }
        Object anchor = root.get("time_anchor");
        if (anchor instanceof Map<?, ?> map) {
            GlossaryEntry entry = new GlossaryEntry(
                    textOr(map.get("term"), "时间口径"),
                    strings(map.get("synonyms")),
                    text(map.get("description")),
                    text(map.get("sql_fragment")),
                    text(map.get("example")));
            result.put(normalize(entry.term()), entry);
            for (String synonym : entry.synonyms()) {
                result.putIfAbsent(normalize(synonym), entry);
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> strings(Object value) {
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>();
            for (Object item : collection) {
                String text = text(item);
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textOr(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
