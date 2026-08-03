package com.agenttrail.support;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 为本地集成测试读取与应用相同的配置键。环境变量优先，其次是根目录
 * {@code application-local.yml}，最后是仓库内的安全默认值。
 */
public final class LocalConfig {

    private static final Properties PROPERTIES = load(
            Path.of("src/main/resources/application.yml"),
            Path.of("application-local.yml"));

    private LocalConfig() {
    }

    static Properties load(Path... paths) {
        Properties merged = new Properties();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                continue;
            }
            YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
            loader.setResources(new FileSystemResource(path));
            Properties loaded = loader.getObject();
            if (loaded != null) {
                loaded.forEach((key, value) -> merged.setProperty(key.toString(), value.toString()));
            }
        }
        return merged;
    }

    static String environmentName(String propertyName) {
        return propertyName.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** @throws IllegalStateException 配置既不在环境变量也不在本地 YAML 中 */
    public static String require(String propertyName) {
        String environmentName = environmentName(propertyName);
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            value = PROPERTIES.getProperty(propertyName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " 未配置——请设置环境变量 "
                    + environmentName + " 或填写 application-local.yml");
        }
        return value;
    }
}
