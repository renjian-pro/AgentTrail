package com.agenttrail.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTemplateTest {

    private static final Path APPLICATION_DEFAULTS =
            Path.of("src/main/resources/application.yml");
    private static final Path LOCAL_TEMPLATE = Path.of("application-local.example.yml");
    private static final Pattern VALUE_KEY = Pattern.compile("@Value\\(\"\\$\\{([^}:]+)");
    private static final Set<String> LOCAL_ONLY_SETTINGS = Set.of(
            "spring.ai.deepseek.api-key",
            "spring.ai.openai.api-key",
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "agenttrail.pgvector.url",
            "agenttrail.pgvector.username",
            "agenttrail.pgvector.password",
            "tavily.api-key",
            "agenttrail.minio.endpoint",
            "agenttrail.minio.access-key",
            "agenttrail.minio.secret-key");

    @Test
    void localTemplateContainsSensitiveSettingsButNoValues() {
        Properties defaults = loadYaml(APPLICATION_DEFAULTS);
        Properties local = loadYaml(LOCAL_TEMPLATE);

        assertThat(LOCAL_ONLY_SETTINGS).allSatisfy(key -> assertThat(local.getProperty(key))
                .as(key + " 的示例值必须留空")
                .isEmpty());
        assertThat(LOCAL_ONLY_SETTINGS).allSatisfy(key -> {
            assertThat(defaults).doesNotContainKey(key);
            assertThat(local).containsKey(key);
        });
    }

    @Test
    void yamlUsesUtf8WithoutPropertyPlaceholderNoise() throws Exception {
        String defaults = Files.readString(APPLICATION_DEFAULTS, StandardCharsets.UTF_8);
        String local = Files.readString(LOCAL_TEMPLATE, StandardCharsets.UTF_8);

        assertThat(defaults).contains("安全默认值");
        assertThat(defaults + local)
                .doesNotContain("${AGENTTRAIL_", "${DASHSCOPE_", "${DEEPSEEK_");
        try (Stream<Path> resources = Files.list(Path.of("src/main/resources"))) {
            assertThat(resources.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("application") && name.endsWith(".properties"));
        }
    }

    @Test
    void everyValueInjectedRuntimeSettingIsDocumentedInYaml() throws Exception {
        Set<String> injectedKeys = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = VALUE_KEY.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    injectedKeys.add(matcher.group(1));
                }
            }
        }

        Properties documented = loadYaml(APPLICATION_DEFAULTS);
        documented.putAll(loadYaml(LOCAL_TEMPLATE));
        assertThat(documented.stringPropertyNames())
                .as("所有 @Value 配置都必须出现在默认配置或本地模板中")
                .containsAll(injectedKeys);
    }

    private static Properties loadYaml(Path path) {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new FileSystemResource(path));
        Properties loaded = loader.getObject();
        Properties normalized = new Properties();
        if (loaded != null) {
            loaded.forEach((key, value) -> normalized.setProperty(key.toString(), value.toString()));
        }
        return normalized;
    }
}
