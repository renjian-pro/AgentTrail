package com.agenttrail.loop.skills;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件系统 ↔ 数据库双存储的对账与请求级热加载（踩坑点 #48）。
 *
 * <p>起真实 MySQL 容器而不是 H2：这张表用到了 {@code ON UPDATE CURRENT_TIMESTAMP}、
 * utf8mb4 下的唯一索引键长限制、{@code TINYINT} 布尔映射，这些在 H2 上的行为和 MySQL 不一致，
 * 用 H2 测出来的"绿"没有意义。建表脚本直接跑 {@code db/schema.sql} 本体，
 * 顺带把 DDL 本身也校验了一遍。
 */
@Testcontainers
class SkillManagerTest {

    /**
     * 数据目录挂 tmpfs：MySQL 首次启动要跑一遍 initdb，落在 Docker Desktop 的虚拟磁盘上
     * 慢到会顶穿默认 120 秒的等待窗口。放内存里既快又符合"测试数据本来就该是一次性的"。
     * 启动窗口同时放宽，避免机器负载高时假失败。
     */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withStartupTimeoutSeconds(300)
            .withConnectTimeoutSeconds(300);

    private static DataSource dataSource;

    @TempDir
    Path skillsRoot;

    private SkillRepository repository;
    private SkillManager manager;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        source.setDriverClassName(MYSQL.getDriverClassName());
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        dataSource = source;
    }

    @BeforeEach
    void resetTable() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE agent_skill").update();
        repository = new SkillRepository(jdbc);
        manager = new SkillManager(skillsRoot, repository);
    }

    // ==================== 对账：发现新增 ====================

    /** 运维绕过上传接口，直接往目录里扔了个技能——定时对账必须能发现它，而不是等重启。 */
    @Test
    void reconcileDiscoversSkillsDroppedStraightOntoDisk() throws IOException {
        writeSkill("pptx", "生成演示文稿");

        SkillReconciliation result = manager.reconcile();

        assertThat(result.discovered()).containsExactly("pptx");
        assertThat(repository.findAll()).singleElement()
                .satisfies(metadata -> {
                    assertThat(metadata.name()).isEqualTo("pptx");
                    assertThat(metadata.description()).isEqualTo("生成演示文稿");
                    assertThat(metadata.enabled()).isTrue();
                    assertThat(metadata.skillPath()).isEqualTo(skillsRoot.resolve("pptx").toAbsolutePath().toString());
                });
    }

    @Test
    void reconcileIsIdempotent() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        manager.reconcile();

        SkillReconciliation second = manager.reconcile();

        assertThat(second.discovered()).isEmpty();
        assertThat(second.orphansRemoved()).isEmpty();
        assertThat(repository.findAll()).hasSize(1);
    }

    /** 只有含 SKILL.md 的目录才是技能，散落的普通目录和文件都不入库。 */
    @Test
    void reconcileIgnoresDirectoriesWithoutSkillFile() throws IOException {
        Files.createDirectories(skillsRoot.resolve("just-a-folder"));
        Files.writeString(skillsRoot.resolve("loose.md"), "散落文件");
        writeSkill("pptx", "生成演示文稿");

        assertThat(manager.reconcile().discovered()).containsExactly("pptx");
    }

    /**
     * 上传解压的临时目录就建在 skills 目录**下面**（踩坑点 #16：跨盘符 move 非空目录在
     * Windows 下会抛异常，所以临时目录不能放 OS 默认位置）。这条约束和定时对账撞在一起：
     * 对账正好在上传解压到一半时跑，会把半成品目录当成一个技能入库。按前缀跳过。
     */
    @Test
    void reconcileSkipsInFlightUploadTempDirectories() throws IOException {
        writeSkillAt(Files.createDirectories(skillsRoot.resolve("skill-upload-12345")), "half-extracted", "半成品");
        writeSkill("pptx", "生成演示文稿");

        assertThat(manager.reconcile().discovered()).containsExactly("pptx");
    }

    /** 目录名本身当不了技能名的（隐藏目录、带穿越字符）一律跳过，绝不让它变成一条 DB 记录。 */
    @Test
    void reconcileSkipsHiddenDirectories() throws IOException {
        writeSkillAt(Files.createDirectories(skillsRoot.resolve(".git")), "git", "不是技能");
        writeSkill("pptx", "生成演示文稿");

        assertThat(manager.reconcile().discovered()).containsExactly("pptx");
    }

    // ==================== 对账：清理孤儿 ====================

    @Test
    void reconcileRemovesRecordsWhoseDirectoryIsGone() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        writeSkill("data-analysis", "查数据");
        manager.reconcile();

        deleteRecursively(skillsRoot.resolve("pptx"));
        SkillReconciliation result = manager.reconcile();

        assertThat(result.orphansRemoved()).containsExactly("pptx");
        assertThat(repository.findAll()).extracting(SkillMetadata::name).containsExactly("data-analysis");
    }

    /**
     * 参考实现里的一个真 bug：扫目录抛 IOException 时只 log 一句就继续往下走，
     * 此时"文件系统上有哪些技能"这份清单是空的/残缺的，紧接着的孤儿清理会把**全表**删掉，
     * 连带运营辛苦配的 enabled 状态一起没了。扫描失败时必须整体放弃这一轮对账。
     */
    @Test
    void reconcileDeletesNothingWhenTheDirectoryCannotBeScanned() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        manager.reconcile();

        SkillManager brokenRoot = new SkillManager(skillsRoot.resolve("does-not-exist"), repository);
        SkillReconciliation result = brokenRoot.reconcile();

        assertThat(result.orphansRemoved()).isEmpty();
        assertThat(repository.findAll()).hasSize(1);
    }

    // ==================== 对账：文件系统是内容的唯一真相 ====================

    /** 描述以磁盘为准刷新，但 enabled 是 DB 独有的状态，对账不能把运营的停用操作覆盖回去。 */
    @Test
    void reconcileRefreshesDescriptionFromDiskButKeepsEnabledFlag() throws IOException {
        writeSkill("pptx", "旧描述");
        manager.reconcile();
        manager.setEnabled("pptx", false);

        writeSkill("pptx", "新描述");
        SkillReconciliation result = manager.reconcile();

        assertThat(result.refreshed()).containsExactly("pptx");
        assertThat(repository.findAll()).singleElement().satisfies(metadata -> {
            assertThat(metadata.description()).isEqualTo("新描述");
            assertThat(metadata.enabled()).isFalse();
        });
    }

    // ==================== 请求级热加载 ====================

    /**
     * 这是 #48 的核心断言：运营在后台把技能停用，**没有重启、没有重新对账**，
     * 下一次装配就看不到它了。做法是每次装配都重新查一次 DB，而不是复用启动时建好的实例。
     */
    @Test
    void togglingEnabledTakesEffectOnTheVeryNextAssembly() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        writeSkill("data-analysis", "查数据");
        manager.reconcile();

        ToolCallback before = manager.buildSkillsTool().orElseThrow();
        assertThat(before.getToolDefinition().description()).contains("pptx").contains("data-analysis");

        manager.setEnabled("pptx", false);

        ToolCallback after = manager.buildSkillsTool().orElseThrow();
        assertThat(after.getToolDefinition().description())
                .doesNotContain("<name>pptx</name>")
                .contains("<name>data-analysis</name>");
        // 是重新装配出来的新实例，不是缓存住的同一个
        assertThat(after).isNotSameAs(before);
    }

    @Test
    void reEnablingBringsTheSkillBackOnTheNextAssembly() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        manager.reconcile();
        manager.setEnabled("pptx", false);
        assertThat(manager.buildSkillsTool()).isEmpty();

        manager.setEnabled("pptx", true);

        assertThat(manager.buildSkillsTool().orElseThrow().getToolDefinition().description())
                .contains("<name>pptx</name>");
    }

    /** 全部停用时这一轮不挂 Skill 工具，而不是抛异常把整个请求打死。 */
    @Test
    void assemblesNoToolWhenEverySkillIsDisabled() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        manager.reconcile();
        manager.setEnabled("pptx", false);

        assertThat(manager.buildSkillsTool()).isEmpty();
        assertThat(manager.enabledSkills()).isEmpty();
    }

    /** 正文改了磁盘上的 SKILL.md，下一轮拿到的就是新正文——DB 里根本不存正文。 */
    @Test
    void picksUpEditedSkillContentWithoutAnyReconcile() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        manager.reconcile();

        Files.writeString(skillsRoot.resolve("pptx").resolve("SKILL.md"), """
                ---
                name: pptx
                description: 生成演示文稿
                ---
                改过的正文
                """);

        assertThat(manager.enabledSkills()).singleElement()
                .satisfies(skill -> assertThat(skill.content()).contains("改过的正文"));
    }

    /**
     * DB 里存着一条 enabled 记录，但目录已经被删了（对账还没跑到）——装配阶段跳过它，
     * 不能因为一条脏记录让整轮请求失败。
     */
    @Test
    void skipsEnabledRecordsWhoseDirectoryVanished() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        writeSkill("data-analysis", "查数据");
        manager.reconcile();

        deleteRecursively(skillsRoot.resolve("pptx"));

        assertThat(manager.enabledSkills()).extracting(Skill::name).containsExactly("data-analysis");
    }

    /**
     * DB 是不可信输入源：有人手工往表里写了一条 name 带 {@code ../} 的记录，
     * 装配时绝不能顺着它 resolve 出 skills 目录之外的路径。
     */
    @Test
    void refusesToLoadSkillWhoseNameInDatabaseWouldEscapeTheRoot() {
        repository.insert("../../etc", "/tmp/whatever", "手工插入的脏数据");

        assertThat(manager.enabledSkills()).isEmpty();
        assertThat(manager.buildSkillsTool()).isEmpty();
    }

    // ==================== 启用状态维护 ====================

    @Test
    void setEnabledRejectsUnknownSkill() {
        assertThatThrownBy(() -> manager.setEnabled("nope", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setEnabledRejectsTraversingName() {
        assertThatThrownBy(() -> manager.setEnabled("../etc", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listReportsBothEnabledAndDisabledSkills() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        writeSkill("data-analysis", "查数据");
        manager.reconcile();
        manager.setEnabled("pptx", false);

        List<SkillMetadata> all = manager.list();

        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(SkillMetadata::enabled).extracting(SkillMetadata::name)
                .containsExactly("data-analysis");
    }

    /** 唯一索引兜住重复入库：同名技能不会产生两条记录。 */
    @Test
    void keepsSkillNameUnique() throws IOException {
        writeSkill("pptx", "生成演示文稿");
        manager.reconcile();
        manager.reconcile();

        assertThat(repository.findAll()).hasSize(1);
    }

    /** 中文技能名（真实存量数据里就有）走完整条链路：入库 → 查询 → 装配。 */
    @Test
    void supportsNonAsciiSkillNames() throws IOException {
        writeSkill("域名创意生成器", "起域名");
        manager.reconcile();

        Optional<ToolCallback> tool = manager.buildSkillsTool();

        assertThat(tool).isPresent();
        assertThat(tool.get().getToolDefinition().description()).contains("域名创意生成器");
        assertThat(tool.get().call("{\"command\":\"域名创意生成器\"}")).contains("技能正文");
    }

    // ==================== 测试夹具 ====================

    private void writeSkill(String name, String description) throws IOException {
        writeSkillAt(Files.createDirectories(skillsRoot.resolve(name)), name, description);
    }

    private void writeSkillAt(Path directory, String name, String description) throws IOException {
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                技能正文-%s
                """.formatted(name, description, name));
    }

    private void deleteRecursively(Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
