package com.agenttrail.loop.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 技能名的路径穿越校验。
 *
 * <p>技能名有三个不可信来源——上传包里 frontmatter 写的 name、HTTP 接口传进来的 name、
 * 数据库里存的 name（DB 可能被别的通道写脏）。这三处最终都会被 resolve 成一个磁盘路径，
 * 所以校验必须放在"名字 → 路径"这唯一的入口上，而不是各调用点各写一遍。
 */
class SkillNamesTest {

    private final Path root = Path.of("/srv/agenttrail/skills");

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "data-analysis", "skill_creator", "域名创意生成器", "a1"})
    void acceptsPlainDirectoryNames(String name) {
        assertThatCode(() -> SkillNames.validate(name)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "..",                      // 直接上跳
            ".",                       // 当前目录
            "../../etc",               // 多级上跳
            "..\\windows",             // Windows 分隔符的上跳
            "a/b",                     // 子路径
            "a\\b",
            "/absolute",
            "C:\\Windows",             // 带盘符：Windows 下 resolve 会直接跳到另一个盘
            ".hidden",                 // 隐藏目录不是技能
            "with\u0000nul"            // NUL 截断，老 JDK/原生调用上会截掉后半段
    })
    void rejectsAnythingThatCanEscapeTheSkillsRoot(String name) {
        assertThatThrownBy(() -> SkillNames.validate(name))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> SkillNames.validate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillNames.validate("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesValidNameUnderTheGivenRoot() {
        assertThat(SkillNames.resolve(root, "pptx")).isEqualTo(root.resolve("pptx"));
    }

    /** 校验和 resolve 必须是同一个入口：能通过 validate 的名字才允许落到磁盘路径上。 */
    @Test
    void resolveRefusesTraversingNames() {
        assertThatThrownBy(() -> SkillNames.resolve(root, "../secrets"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsWhetherANameIsSafeWithoutThrowing() {
        assertThat(SkillNames.isValid("pptx")).isTrue();
        assertThat(SkillNames.isValid("../x")).isFalse();
        assertThat(SkillNames.isValid(null)).isFalse();
    }
}
