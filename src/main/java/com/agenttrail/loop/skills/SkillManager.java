package com.agenttrail.loop.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 技能的双存储管理层：文件系统存内容，数据库存状态，两边定时对账（踩坑点 #48）。
 *
 * <h2>为什么需要对账</h2>
 * <p>技能有两个数据源，谁都不是完整的：磁盘上有正文却没有"启不启用"，数据库里有启用状态
 * 却没有正文。如果只在应用启动时同步一次，运维直接往 skills 目录扔一个新文件夹
 * （不走上传接口）——这个技能永远不会被发现，除非重启整个服务。所以要有
 * {@link #reconcile()} 定时跑，让"绕过 API 直接动底层存储"也能最终被系统看见。
 *
 * <h2>为什么还要请求级重建</h2>
 * <p>光有定时对账不够。运营在后台把某个技能停用，如果工具实例是启动时建好、之后一直复用的，
 * 那要等到下次重启才生效。所以 {@link #buildSkillsTool()} 在一次对话开始时读取一次数据库，
 * 按当时启用的技能装配一个工具，并由循环在该对话的所有轮次复用这份快照。
 * 下一次对话会重新读取，既保证会话内语义稳定，也不让每轮模型调用重复访问数据库。
 *
 * <h2>三条和参考实现不同的地方</h2>
 * <ul>
 *   <li><b>扫描失败就整轮放弃</b>。参考实现扫目录抛 IOException 时只 log 一句继续往下走，
 *       此时"磁盘上有哪些技能"这份清单是残缺的，紧接着的孤儿清理会把全表删掉，
 *       连运营配的启用状态一起没。这是个真 bug，且只在磁盘出问题时才发作。
 *   <li><b>跳过上传临时目录</b>。上传解压的临时目录按踩坑点 #16 必须建在 skills 目录**下面**
 *       （跨盘符 move 非空目录在 Windows 下会抛异常），于是它和对账天然会撞上：
 *       对账正好在解压到一半时跑，会把半成品目录当成一个技能入库。
 *   <li><b>装配时按"根目录 + 技能名"重新解析路径，不信 DB 里存的 skill_path</b>。
 *       一是可移植——存量数据里的绝对路径带着别人机器的盘符，换台机器就全是坏路径；
 *       二是安全——DB 是可以被别的通道写脏的输入源，直接拿它存的路径去读文件，
 *       等于把路径穿越的入口开在了数据库上。
 * </ul>
 */
public class SkillManager {

    /**
     * 上传解压用的临时目录前缀。这个前缀是 skills 目录下的保留字，不能当技能名用。
     * 之所以临时目录非得建在 skills 目录里，见踩坑点 #16。
     */
    static final String UPLOAD_TEMP_PREFIX = "skill-upload-";

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final Path skillsDirectory;

    private final SkillRepository repository;

    public SkillManager(Path skillsDirectory, SkillRepository repository) {
        this.skillsDirectory = skillsDirectory;
        this.repository = repository;
    }

    // ==================== 请求级装配 ====================

    /**
     * 按对话开始时的启用状态装配 Skill 工具。调用方应在一次对话内缓存返回值，
     * 下一次对话重新调用本方法获取新的技能快照。
     *
     * @return 有启用技能时是唯一的那个工具；一个都没有时为空（这一轮就不挂 Skill 工具）
     */
    public Optional<ToolCallback> buildSkillsTool() {
        return SkillsTool.of(enabledSkills());
    }

    /**
     * 读一次数据库拿启用清单，再逐个从磁盘装载正文。
     *
     * <p>每次新对话重读磁盘，换来的是改完 SKILL.md 后下一次对话即可生效，
     * 既不用重启也不用等定时对账。对话内不重复读盘，避免多轮 ReAct 循环产生不一致的技能快照。
     *
     * <p>坏掉的记录逐条跳过而不是整体失败：一条脏数据不该让整轮对话没有技能可用。
     */
    public List<Skill> enabledSkills() {
        List<Skill> skills = new ArrayList<>();
        for (SkillMetadata metadata : repository.findEnabled()) {
            Path directory;
            try {
                // 用 name 重新解析，不用 DB 里的 skill_path —— DB 是不可信输入源
                directory = SkillNames.resolve(skillsDirectory, metadata.name());
            } catch (IllegalArgumentException illegalName) {
                log.warn("数据库里的技能名不合法，已跳过: {} ({})", metadata.name(), illegalName.getMessage());
                continue;
            }
            if (!Files.isDirectory(directory)) {
                // 目录被删了但对账还没跑到：跳过，等下一轮对账清理这条孤儿记录
                log.warn("已启用的技能目录不存在，已跳过: {} -> {}", metadata.name(), directory);
                continue;
            }
            Skill.load(directory).ifPresent(skills::add);
        }
        return skills;
    }

    // ==================== 对账 ====================

    /**
     * 定时对账的入口。它只是 {@link #reconcile()} 的 void 包装——{@code @Scheduled} 的语义是
     * "定时执行"，返回值没有接收方；而 {@link #reconcile()} 要把动过什么返回给调用方和测试。
     * 两件事分成两个方法，比让一个方法同时背这两种身份清楚。
     */
    @Scheduled(fixedDelayString = "${agenttrail.skills.reconcile-interval-ms:180000}",
            initialDelayString = "${agenttrail.skills.reconcile-interval-ms:180000}")
    public void scheduledReconcile() {
        reconcile();
    }

    /**
     * 文件系统 ↔ 数据库双向对账。
     *
     * <p>三条规则，方向各不相同，因为两边各自是不同东西的真相：
     * <ul>
     *   <li>磁盘有、DB 没有 → 入库并默认启用（磁盘是"技能存在与否"的真相）
     *   <li>DB 有、磁盘没有 → 判定为孤儿记录，删掉
     *   <li>两边都有 → 用磁盘刷新描述和路径，但**保留 DB 里的启用状态**
     *       （DB 是"启不启用"的真相，磁盘上根本没有这个信息）
     * </ul>
     */
    public SkillReconciliation reconcile() {
        Optional<Set<String>> scanned = scanSkillDirectories();
        if (scanned.isEmpty()) {
            // 扫描失败时**什么都不做**。拿一份残缺的磁盘清单去做孤儿清理会删掉整张表
            return SkillReconciliation.none();
        }
        Set<String> onDisk = scanned.get();

        List<SkillMetadata> inDatabase = repository.findAll();
        Set<String> knownNames = new LinkedHashSet<>(inDatabase.stream().map(SkillMetadata::name).toList());

        List<String> discovered = new ArrayList<>();
        List<String> refreshed = new ArrayList<>();
        List<String> orphansRemoved = new ArrayList<>();

        for (String name : onDisk) {
            Path directory = skillsDirectory.resolve(name);
            Skill skill = Skill.load(directory).orElse(null);
            if (skill == null) {
                continue;
            }
            String path = directory.toAbsolutePath().toString();
            if (!knownNames.contains(name)) {
                repository.insert(name, path, skill.description());
                discovered.add(name);
                log.info("发现新技能并入库（默认启用）: {}", name);
                continue;
            }
            SkillMetadata existing = inDatabase.stream()
                    .filter(metadata -> metadata.name().equals(name))
                    .findFirst()
                    .orElseThrow();
            if (!skill.description().equals(nullToEmpty(existing.description()))
                    || !path.equals(nullToEmpty(existing.skillPath()))) {
                repository.refresh(name, path, skill.description());
                refreshed.add(name);
            }
        }

        for (SkillMetadata metadata : inDatabase) {
            if (!onDisk.contains(metadata.name())) {
                repository.deleteByName(metadata.name());
                orphansRemoved.add(metadata.name());
                log.warn("技能目录已不存在，清理孤儿记录: {}", metadata.name());
            }
        }

        return new SkillReconciliation(discovered, orphansRemoved, refreshed);
    }

    /**
     * 扫出 skills 根目录下所有"看起来是技能"的一级子目录名。
     *
     * @return 扫描成功时是目录名集合（可能为空集，那是"磁盘上一个技能都没有"的合法结论）；
     *         扫描失败时为空 Optional——这和"扫出零个"是两回事，调用方必须区别对待
     */
    private Optional<Set<String>> scanSkillDirectories() {
        if (!Files.isDirectory(skillsDirectory)) {
            log.warn("技能根目录不存在，跳过本轮对账: {}", skillsDirectory.toAbsolutePath());
            return Optional.empty();
        }
        Set<String> names = new LinkedHashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDirectory, Files::isDirectory)) {
            for (Path directory : stream) {
                String name = directory.getFileName().toString();
                if (name.startsWith(UPLOAD_TEMP_PREFIX)) {
                    // 正在解压的上传包，还不是一个完整技能（踩坑点 #16 让它必须待在这个目录下）
                    continue;
                }
                if (!SkillNames.isValid(name)) {
                    continue;
                }
                if (Files.isRegularFile(directory.resolve(Skill.SKILL_FILE))) {
                    names.add(name);
                }
            }
        } catch (IOException unreadable) {
            log.warn("扫描技能目录失败，跳过本轮对账（不做任何删除）: {}", unreadable.toString());
            return Optional.empty();
        }
        return Optional.of(names);
    }

    // ==================== 运营接口 ====================

    public List<SkillMetadata> list() {
        return repository.findAll();
    }

    /**
     * 切换启用状态。改完不需要重启，也不需要等对账——下一次 {@link #buildSkillsTool()} 就是新状态。
     *
     * @throws IllegalArgumentException 技能名不合法，或库里没有这个技能
     */
    public boolean setEnabled(String name, boolean enabled) {
        SkillNames.validate(name);
        if (!repository.setEnabled(name, enabled)) {
            throw new IllegalArgumentException("技能不存在: " + name);
        }
        log.info("技能启用状态切换: {} -> enabled={}", name, enabled);
        return enabled;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
