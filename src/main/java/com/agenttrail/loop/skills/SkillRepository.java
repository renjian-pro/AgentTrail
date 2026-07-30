package com.agenttrail.loop.skills;

import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@code agent_skill} 表的读写。建表语句在 {@code db/schema.sql}。
 *
 * <p>刻意不做自动建表。参考实现在 {@code @PostConstruct} 里 try/catch 地 CREATE TABLE、
 * 再 try/catch 地补列、失败就吞掉——好处是省事，代价是**表结构的真相分散在代码里**，
 * DBA 看不到、变更没有版本、每次启动都在猜"这次是不是已经建过了"。表结构统一放
 * schema.sql，让它成为唯一一份可评审的 DDL。
 */
public class SkillRepository {

    private final JdbcClient jdbc;

    public SkillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SkillRepository(DataSource dataSource) {
        this(JdbcClient.create(dataSource));
    }

    public List<SkillMetadata> findAll() {
        return jdbc.sql("SELECT name, skill_path, description, enabled FROM agent_skill ORDER BY name")
                .query(SkillRepository::mapRow)
                .list();
    }

    /**
     * 装配一次对话请求要用的技能时走这条查询。
     *
     * <p>按 name 排序不是可有可无的——装配顺序决定了工具描述里技能的排列顺序，
     * 而工具描述是提示词前缀的一部分。不加 ORDER BY 就把提示词前缀的稳定性
     * 交给了数据库的返回顺序，prompt 缓存会莫名其妙地时好时坏。
     */
    public List<SkillMetadata> findEnabled() {
        return jdbc.sql("SELECT name, skill_path, description, enabled FROM agent_skill "
                        + "WHERE enabled = 1 ORDER BY name")
                .query(SkillRepository::mapRow)
                .list();
    }

    /** 新发现的技能默认启用——运维把目录放进去就是想让它生效，再要求去后台点一次开关是多余的。 */
    public void insert(String name, String skillPath, String description) {
        jdbc.sql("INSERT INTO agent_skill (name, skill_path, description, enabled) VALUES (?, ?, ?, 1)")
                .params(name, skillPath, description)
                .update();
    }

    /** 只刷新来自磁盘的字段。{@code enabled} 不在这条语句里，是为了让"不覆盖启用状态"成为语法上的事实。 */
    public void refresh(String name, String skillPath, String description) {
        jdbc.sql("UPDATE agent_skill SET skill_path = ?, description = ? WHERE name = ?")
                .params(skillPath, description, name)
                .update();
    }

    public void deleteByName(String name) {
        jdbc.sql("DELETE FROM agent_skill WHERE name = ?").param(name).update();
    }

    /** @return 是否真的命中了一条记录 */
    public boolean setEnabled(String name, boolean enabled) {
        return jdbc.sql("UPDATE agent_skill SET enabled = ? WHERE name = ?")
                .params(enabled ? 1 : 0, name)
                .update() > 0;
    }

    private static SkillMetadata mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SkillMetadata(
                rs.getString("name"),
                rs.getString("skill_path"),
                rs.getString("description"),
                rs.getInt("enabled") == 1);
    }
}
