package com.agenttrail.loop.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 一个装载完毕的技能：磁盘上一个含 {@code SKILL.md} 的目录。
 *
 * <p>{@code name}/{@code description} 是给模型"挑技能"用的摘要，会渲染进工具描述；
 * {@code content} 是 SKILL.md 的正文，只有模型真的调用了 Skill 工具才会返回——
 * 渐进式披露的整个意义就是让这两部分分开走。
 *
 * @param name        技能名，也是模型调用 Skill 工具时要传的值
 * @param description 一句话描述，模型据此判断这个技能能不能干眼下这件事
 * @param directory   技能工作目录，随正文一起返回给模型；技能里的模板/脚本要靠它拼路径
 * @param content     SKILL.md 正文（不含 frontmatter）
 */
public record Skill(String name, String description, Path directory, String content) {

    /** Claude Code 的 Skills 规范：一个技能就是一个含 SKILL.md 的目录。 */
    public static final String SKILL_FILE = "SKILL.md";

    private static final Logger log = LoggerFactory.getLogger(Skill.class);

    /**
     * 从技能目录装载。目录里没有 SKILL.md、或者读不出来，都返回空而不是抛异常——
     * 一个坏掉的技能目录不该让整轮请求的工具装配失败。
     */
    public static Optional<Skill> load(Path directory) {
        Path skillFile = directory.resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillFile)) {
            return Optional.empty();
        }
        String markdown;
        try {
            markdown = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            log.warn("技能文件读取失败，跳过: {} ({})", skillFile, unreadable.toString());
            return Optional.empty();
        }

        SkillDocument document = SkillDocument.parse(markdown);
        // frontmatter 没写 name 就退回目录名。参考实现在这种情况下返回空字符串，
        // 于是这个技能以空串为键进了 map——描述里列着它，模型却永远调不到。
        String name = blankToNull(document.value("name"));
        if (name == null) {
            name = directory.getFileName().toString();
        }
        String description = blankToNull(document.value("description"));
        return Optional.of(new Skill(name.trim(), description == null ? "" : description.trim(),
                directory, document.body()));
    }

    /**
     * 渲染成工具描述里的一段 XML。
     *
     * <p>用 XML 而不是 JSON/Markdown：这段内容要嵌进一大段自然语言的工具说明里，
     * 带闭合标签的结构在视觉上和周围的说明文字区分得最清楚，模型不容易把清单里的名字
     * 和说明文字混起来读（踩坑点 #15）。
     *
     * <p>只渲染 name + description 两个字段，不像参考实现那样把整段 frontmatter 倒出来：
     * 渐进式披露的前提是**摘要要足够小**，把 version/license/allowed-tools 这类
     * 与"该不该选这个技能"无关的键一起塞进去，是在往每一次请求的提示词前缀里灌噪音。
     */
    public String toXml() {
        return """
                <skill>
                  <name>%s</name>
                  <description>%s</description>
                </skill>""".formatted(escapeXml(name), escapeXml(description));
    }

    /**
     * 技能的 name/description 是运营写在 Markdown 里的自由文本，出现 {@code <} 或 {@code &}
     * 会把上面那段 XML 撑破，让模型读到一个结构错乱的技能清单。参考实现直接拼接，没有转义。
     */
    private static String escapeXml(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
