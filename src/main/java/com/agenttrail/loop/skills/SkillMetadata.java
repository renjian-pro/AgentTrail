package com.agenttrail.loop.skills;

/**
 * 技能在数据库里的那一半——**只有查询和运营用得上的元数据**，没有正文。
 *
 * <p>双存储的分工是刻意的，不是没来得及统一：
 * <ul>
 *   <li>文件系统是 {@code SKILL.md} 内容的唯一真相。正文动辄上千行，还带着 references/
 *       scripts/ 一堆资源文件，塞进数据库既不好维护，也让"直接往目录里扔一个技能"这种
 *       运维方式没法用。
 *   <li>数据库只存 {@code enabled} 这类**文件系统里根本没有的状态**，外加 name/description
 *       这些为了列表页和装配查询而冗余的字段。冗余的那部分每次对账都从磁盘刷新，
 *       磁盘永远赢。
 * </ul>
 *
 * @param name        技能名，等于 skills 根目录下的子目录名（唯一索引）
 * @param skillPath   技能目录绝对路径，运维排查用；装配时并不信它，见 {@link SkillManager}
 * @param description 来自 SKILL.md frontmatter，每次对账从磁盘刷新
 * @param enabled     是否启用。这是 DB 独有的状态，对账时绝不能被磁盘覆盖掉
 */
import java.util.Set;

public record SkillMetadata(String name, String skillPath, String description, boolean enabled,
                            String version, SkillPermission permission, SkillResourceLimits limits) {
    public SkillMetadata(String name, String skillPath, String description, boolean enabled) {
        this(name, skillPath, description, enabled, "0.0.0", SkillPermission.unrestricted(),
                SkillResourceLimits.defaults());
    }

    public SkillMetadata {
        version = version == null || version.isBlank() ? "0.0.0" : version;
        permission = permission == null ? SkillPermission.unrestricted() : permission;
        limits = limits == null ? SkillResourceLimits.defaults() : limits;
    }

    public record SkillPermission(Set<String> allowedTools, Set<String> allowedResources) {
        public SkillPermission {
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            allowedResources = allowedResources == null ? Set.of() : Set.copyOf(allowedResources);
        }
        public static SkillPermission unrestricted() { return new SkillPermission(Set.of(), Set.of()); }
    }

    public record SkillResourceLimits(long maxTokens, long timeoutSeconds) {
        public SkillResourceLimits {
            if (maxTokens < 0 || timeoutSeconds < 0) throw new IllegalArgumentException("limits must be non-negative");
        }
        public static SkillResourceLimits defaults() { return new SkillResourceLimits(0, 120); }
    }
}
