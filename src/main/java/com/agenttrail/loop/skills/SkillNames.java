package com.agenttrail.loop.skills;

import java.nio.file.Path;

/**
 * 技能名的合法性校验——技能名唯一的语义是"skills 根目录下的一级子目录名"，别的都不是。
 *
 * <p>为什么必须收在一处：技能名有三个互不信任的来源——上传包里 frontmatter 自称的 name、
 * 管理接口传进来的 name、数据库里存着的 name（DB 可能被别的通道写脏）。它们最终都会被
 * resolve 成一个磁盘路径去读写文件，所以校验要卡在"名字 → 路径"这唯一的通道上，
 * 而不是指望每个调用点各自记得校验一次——参考实现就是各调用点各写各的，
 * 结果定时对账那条路径上根本没校验。
 *
 * <p>校验用**白名单式的拒绝规则**而不是只查 {@code ..}：路径分隔符（两种）、盘符冒号、
 * NUL 字节、以点开头，全部拒掉。只查 {@code ..} 会漏掉 {@code C:\Windows} 这种
 * Windows 下 resolve 会直接跳到另一个盘的写法。
 */
public final class SkillNames {

    private SkillNames() {
    }

    /**
     * @throws IllegalArgumentException 名字不能安全地当成一个一级子目录名时
     */
    public static void validate(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("技能名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.startsWith(".")) {
            // 同时挡掉 "."、".."、以及 ".git" 这类不是技能的隐藏目录
            throw new IllegalArgumentException("非法技能名（不能以点开头）: " + name);
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains(":")
                || trimmed.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("非法技能名（含路径分隔符或盘符）: " + name);
        }
    }

    /** 不抛异常的版本，给"遍历时跳过不合法项"这类场景用。 */
    public static boolean isValid(String name) {
        try {
            validate(name);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * 校验之后再 resolve，并二次确认结果确实落在 root 之内。
     *
     * <p>二次确认不是多余——校验规则是黑名单式的字符判断，而 {@code normalize} + {@code startsWith}
     * 是对最终路径的事实判断。两道都过了才允许读磁盘。
     */
    public static Path resolve(Path root, String name) {
        validate(name);
        Path normalizedRoot = root.normalize();
        Path resolved = normalizedRoot.resolve(name.trim()).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法技能名（解析后逃出了技能根目录）: " + name);
        }
        return resolved;
    }
}
