package com.agenttrail.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 读取 {@code secrets.properties}（未提交进仓库，见 .gitignore）里的真实凭据，供不经过
 * Spring {@code spring.config.import} 装配、直接手写构造真实客户端的集成测试使用
 * （issue #26 的 RAG 管线测试就是这种情况——它不是 {@code @SpringBootTest}，没有
 * 现成的 Bean 可注入）。
 *
 * <p>先看环境变量，环境变量没有再看 {@code secrets.properties}——和 {@code application.properties}
 * 里 {@code ${DASHSCOPE_API_KEY:}} 这套约定保持一致。
 */
public final class Secrets {

    private static final Properties PROPERTIES = load();

    private Secrets() {
    }

    private static Properties load() {
        Properties properties = new Properties();
        Path path = Path.of("secrets.properties");
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return properties;
    }

    /** @throws IllegalStateException key 既不在环境变量也不在 secrets.properties 里 */
    public static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = PROPERTIES.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    key + " 未配置——这个测试需要真实凭据，请在 secrets.properties 或环境变量里提供");
        }
        return value;
    }
}
