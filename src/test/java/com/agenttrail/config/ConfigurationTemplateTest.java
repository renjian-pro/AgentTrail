package com.agenttrail.config;

import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTemplateTest {

    private static final Path APPLICATION_TEMPLATE =
            Path.of("src/main/resources/application-example.properties");
    private static final Pattern VALUE_KEY = Pattern.compile("@Value\\(\"\\$\\{([^}:]+)");

    @Test
    void sensitiveApplicationSettingsHaveNoCommittedDefaultValue() throws Exception {
        Properties properties = load(APPLICATION_TEMPLATE);
        Map<String, String> sensitiveSettings = Map.ofEntries(
                Map.entry("deepseek.api-key", "${DEEPSEEK_API_KEY:}"),
                Map.entry("spring.ai.deepseek.api-key", "${DEEPSEEK_API_KEY:}"),
                Map.entry("spring.ai.openai.api-key", "${DASHSCOPE_API_KEY:}"),
                Map.entry("spring.datasource.url", "${AGENTTRAIL_DB_URL:}"),
                Map.entry("spring.datasource.username", "${AGENTTRAIL_DB_USERNAME:}"),
                Map.entry("spring.datasource.password", "${AGENTTRAIL_DB_PASSWORD:}"),
                Map.entry("agenttrail.pgvector.url", "${AGENTTRAIL_PGVECTOR_URL:}"),
                Map.entry("agenttrail.pgvector.username", "${AGENTTRAIL_PGVECTOR_USERNAME:}"),
                Map.entry("agenttrail.pgvector.password", "${AGENTTRAIL_PGVECTOR_PASSWORD:}"),
                Map.entry("tavily.api-key", "${TAVILY_API_KEY:}"),
                Map.entry("agenttrail.minio.endpoint", "${AGENTTRAIL_MINIO_ENDPOINT:}"),
                Map.entry("agenttrail.minio.access-key", "${AGENTTRAIL_MINIO_ACCESS_KEY:}"),
                Map.entry("agenttrail.minio.secret-key", "${AGENTTRAIL_MINIO_SECRET_KEY:}"),
                Map.entry("agenttrail.ppt.image.api-key", "${DASHSCOPE_API_KEY:}"),
                Map.entry("agenttrail.ppt.image.minio-endpoint", "${AGENTTRAIL_MINIO_ENDPOINT:}"),
                Map.entry("agenttrail.ppt.image.minio-access-key", "${AGENTTRAIL_MINIO_ACCESS_KEY:}"),
                Map.entry("agenttrail.ppt.image.minio-secret-key", "${AGENTTRAIL_MINIO_SECRET_KEY:}"));

        assertThat(properties).containsAllEntriesOf(sensitiveSettings);
    }

    @Test
    void secretExampleContainsNamesButNoSecretValues() throws Exception {
        Properties properties = load(Path.of("secrets.properties.example"));

        assertThat(properties)
                .containsKeys("DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY", "TAVILY_API_KEY",
                        "AGENTTRAIL_DB_PASSWORD", "AGENTTRAIL_PGVECTOR_PASSWORD",
                        "AGENTTRAIL_MINIO_ACCESS_KEY", "AGENTTRAIL_MINIO_SECRET_KEY");
        assertThat(properties.stringPropertyNames())
                .filteredOn(key -> key.contains("KEY") || key.contains("PASSWORD"))
                .allSatisfy(key -> assertThat(properties.getProperty(key))
                        .as(key + " 示例值必须留空")
                        .isEmpty());
    }

    @Test
    void everyValueInjectedRuntimeSettingIsDocumentedInTheTemplate() throws Exception {
        Set<String> injectedKeys = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = VALUE_KEY.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    injectedKeys.add(matcher.group(1));
                }
            }
        }

        String template = Files.readString(APPLICATION_TEMPLATE, StandardCharsets.UTF_8);
        assertThat(injectedKeys).allSatisfy(key -> assertThat(template)
                .as(key + " 必须出现在 application-example.properties")
                .contains(key + "="));
    }

    private static Properties load(Path path) throws Exception {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
