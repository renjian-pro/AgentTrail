package com.agenttrail.loop.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 目录白名单沙箱：把"模型给的一个字符串路径"翻译成"一个确定落在允许范围内的绝对路径"。
 *
 * <p>踩坑点 #41：文件类工具的目录白名单是把能力对外暴露（MCP Server）时的最低安全线。
 * 内部自用时可以信任调用方，一旦对外暴露，威胁模型完全变了——不限制目录范围等于把整个
 * 文件系统交出去。所以边界校验单独成类：文件工具和搜索工具共用同一套判定，
 * 不给"某个工具忘了校验"留缝隙。
 *
 * <p>三条容易被绕过的路径，这里都堵死了：
 * <ol>
 *   <li>{@code ../} 路径穿越 —— 先 {@code normalize()} 再比对，而不是拿原始字符串比
 *   <li>共享字符串前缀的兄弟目录 —— 用 {@link Path#startsWith(Path)} 按**路径分段**比，
 *       字符串前缀判断会把 {@code /data/root-evil} 误判成 {@code /data/root} 的子目录
 *   <li>符号链接逃逸 —— 只做字符串归一化挡不住软链，比对前先把真实路径解出来
 * </ol>
 */
public final class PathSandbox {

    /** 空表示不设约束（单机自用），此时不做边界校验。 */
    private final List<Path> allowedRoots;

    /** 相对路径的解析基准。设了白名单时是第一个白名单目录，否则是 JVM 当前目录。 */
    private final Path baseDir;

    private PathSandbox(List<Path> allowedRoots, Path baseDir) {
        this.allowedRoots = allowedRoots;
        this.baseDir = baseDir;
    }

    /**
     * 限定在给定目录树内。第一个目录同时作为相对路径的解析基准。
     *
     * @param allowedDirs 允许访问的目录；为空等价于 {@link #unrestricted()}
     */
    public static PathSandbox restrictedTo(String... allowedDirs) {
        if (allowedDirs == null || allowedDirs.length == 0) {
            return unrestricted();
        }
        List<Path> roots = Arrays.stream(allowedDirs)
                .filter(Objects::nonNull)
                .filter(dir -> !dir.isBlank())
                .map(PathSandbox::canonicalize)
                .distinct()
                .toList();
        return roots.isEmpty() ? unrestricted() : new PathSandbox(roots, roots.get(0));
    }

    /**
     * 不做边界校验。
     *
     * <p>保留这个口子是为了单机自用场景（Agent 就是要能读整个工程），
     * 但一旦这些工具被包装成 MCP Server 对外暴露，必须显式设白名单（#41）。
     */
    public static PathSandbox unrestricted() {
        return new PathSandbox(List.of(), Paths.get("").toAbsolutePath().normalize());
    }

    public Path baseDir() {
        return baseDir;
    }

    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /**
     * 把模型传来的路径解析成绝对路径并做边界校验。
     *
     * @param rawPath 绝对路径或相对路径；null / 空白解析为基准目录
     * @return 归一化后的绝对路径
     * @throws SandboxViolationException 路径越出白名单
     */
    public Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return baseDir;
        }
        String trimmed = rawPath.trim();
        // '~' 展开依赖 shell/用户主目录，语义在沙箱里没有意义，直接拒掉而不是猜它想去哪
        if (trimmed.startsWith("~")) {
            throw new SandboxViolationException("不支持以 '~' 开头的路径：" + rawPath);
        }
        Path candidate = Paths.get(trimmed);
        Path absolute = candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).normalize();
        return requireInside(absolute);
    }

    /**
     * 校验一个已经是绝对路径的位置是否落在白名单内（glob 的搜索根走这个入口）。
     *
     * @return 原样返回入参，方便链式调用
     * @throws SandboxViolationException 路径越出白名单
     */
    public Path requireInside(Path absolutePath) {
        if (allowedRoots.isEmpty()) {
            return absolutePath;
        }
        Path probe = resolveSymbolicLinks(absolutePath);
        boolean inside = allowedRoots.stream().anyMatch(probe::startsWith);
        if (!inside) {
            throw new SandboxViolationException(
                    "路径 '" + absolutePath + "' 不在允许访问的目录内：" + allowedRoots);
        }
        return absolutePath;
    }

    /**
     * 解析路径上的符号链接，得到用于边界比对的"真实位置"。
     *
     * <p>目标路径可能还不存在（write_file 就是要创建它），所以从最近的**已存在祖先**
     * 开始解真实路径，再把剩下的段拼回去——这样既能挡住"白名单里放一个指向外部的软链"，
     * 又不会因为文件还没创建就误判。
     */
    private static Path resolveSymbolicLinks(Path absolutePath) {
        Deque<String> pendingNames = new ArrayDeque<>();
        Path existing = absolutePath;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name == null) {
                break;
            }
            pendingNames.push(name.toString());
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolutePath;
        }
        try {
            Path real = existing.toRealPath();
            for (String name : pendingNames) {
                real = real.resolve(name);
            }
            return real;
        } catch (IOException unreadable) {
            // 解不出真实路径（权限不足等）时按归一化路径判定，宁可拒错也不放行
            return absolutePath;
        }
    }

    /** 白名单目录本身也要解成真实路径，否则两边一个走软链一个不走，比对必然对不上。 */
    private static Path canonicalize(String dir) {
        Path absolute = Paths.get(dir).toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException notYetExisting) {
            return absolute;
        }
    }
}
