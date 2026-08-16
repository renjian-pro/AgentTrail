package com.agenttrail.loop.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 外置提示词的加载入口（issue #100）。
 *
 * <p><b>为什么要外置</b>：Golden 评测要能回答"这次分数变化是提示词改动引起的吗"。提示词以
 * {@code static final String} 存在时回答不了——改动只留在 git diff 里，{@code agent_trace} 记的
 * {@code input_data} 是渲染后的完整消息历史，没有"这轮用的哪一版"这个可归因的标识。
 *
 * <p><b>只从 classpath 读文件，没有 DB 覆盖层</b>（requirements §6.3 明确否决）：版本可追溯、
 * 可 code review、可回滚，代价是改提示词要重新构建——本地几十秒，可接受。多一个可写脏的
 * 输入源换来的"不重启就能试"，在这个项目里不划算。
 *
 * <p><b>版本号和哈希都记，冲突时以哈希为准</b>：只靠手工版本号一定会有人忘记改，那一轮评测
 * 就归因错了；只靠哈希则人读不出新旧。{@link #verifyAgainst} 用一份 lockfile 把"改了正文
 * 但没改版本号"变成启动期的一条 WARN，而不是三个月后对着评测曲线猜。
 */
public final class PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);
    private static final String LOCATION_PATTERN = "classpath*:prompts/**/*.md";
    private static final int HASH_LENGTH = 8;

    private final Map<String, PromptDefinition> byId;

    private PromptRegistry(Map<String, PromptDefinition> byId) {
        this.byId = Map.copyOf(byId);
    }

    public static PromptRegistry loadFromClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION_PATTERN);
            Map<String, PromptDefinition> loaded = new LinkedHashMap<>();
            for (Resource resource : resources) {
                PromptDefinition definition = parse(readAll(resource), resource.getFilename());
                PromptDefinition duplicate = loaded.put(definition.id(), definition);
                if (duplicate != null) {
                    throw new IllegalStateException("提示词 id 重复: " + definition.id());
                }
            }
            log.info("已加载 {} 份外置提示词", loaded.size());
            return new PromptRegistry(loaded);
        } catch (IOException failure) {
            throw new IllegalStateException("提示词加载失败", failure);
        }
    }

    /** 找不到直接抛——提示词缺失是装配错误，不是可以静默降级的运行时情况。 */
    public PromptDefinition get(String id) {
        PromptDefinition definition = byId.get(id);
        if (definition == null) {
            throw new NoSuchElementException("未知的提示词 id: " + id + "（已加载: " + byId.keySet() + "）");
        }
        return definition;
    }

    public String text(String id) {
        return get(id).text();
    }

    public List<PromptDefinition> all() {
        return List.copyOf(byId.values());
    }

    /**
     * 对照 lockfile 检查，返回所有不一致的说明（空表示一致）。
     *
     * <p>只有"版本号没变但正文变了"是真问题——那说明有人改了提示词却没声明，评测归因会指向
     * 错误的版本。版本号变了是正常的有意改动，只需要更新 lockfile。
     *
     * @param locked id → {@code version#hash}
     */
    public List<String> verifyAgainst(Map<String, String> locked) {
        List<String> problems = new java.util.ArrayList<>();
        for (PromptDefinition definition : byId.values()) {
            String lockedStamp = locked.get(definition.id());
            if (lockedStamp == null) {
                problems.add("新增提示词未登记进 lockfile: " + definition.id() + " -> "
                        + definition.version() + "#" + definition.hash());
                continue;
            }
            String[] parts = lockedStamp.split("#", 2);
            String lockedVersion = parts[0];
            String lockedHash = parts.length > 1 ? parts[1] : "";
            if (lockedVersion.equals(definition.version()) && !lockedHash.equals(definition.hash())) {
                problems.add("提示词正文变了但版本号没动: " + definition.id()
                        + "（lockfile " + lockedStamp + "，实际 " + definition.version() + "#" + definition.hash()
                        + "）——改完正文请把 version 往上加一档，否则这一轮评测会归因到旧版本");
            } else if (!lockedVersion.equals(definition.version())) {
                problems.add("提示词版本已更新，请同步 lockfile: " + definition.id()
                        + "（lockfile " + lockedStamp + "，实际 " + definition.version() + "#" + definition.hash() + "）");
            }
        }
        return List.copyOf(problems);
    }

    /** 启动时喊一嗓子；不阻断启动——提示词版本对不上是工程纪律问题，不是不能服务的故障。 */
    public void warnOnDrift(Map<String, String> locked) {
        verifyAgainst(locked).forEach(log::warn);
    }

    private static String readAll(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * front matter 只支持 {@code id} 和 {@code version} 两个键，刻意不引 YAML 解析器——
     * 提示词文件的头部不该长到需要一个解析器，能长到那个程度说明它在承担配置的职责，那是设计跑偏了。
     */
    private static PromptDefinition parse(String raw, String filename) {
        String normalized = raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new IllegalStateException("提示词缺少 front matter: " + filename);
        }
        int end = normalized.indexOf("\n---\n", 3);
        if (end < 0) {
            throw new IllegalStateException("提示词 front matter 没有闭合: " + filename);
        }
        String header = normalized.substring(4, end);
        String body = normalized.substring(end + 5).strip();
        String id = headerValue(header, "id", filename);
        String version = headerValue(header, "version", filename);
        return new PromptDefinition(id, version, sha256Prefix(body), body);
    }

    private static String headerValue(String header, String key, String filename) {
        for (String line : header.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(key + ":")) {
                return trimmed.substring(key.length() + 1).strip();
            }
        }
        throw new IllegalStateException("提示词 front matter 缺少 " + key + ": " + filename);
    }

    private static String sha256Prefix(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 未提供 SHA-256", impossible);
        }
    }
}
