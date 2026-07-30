package com.agenttrail.loop.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

/**
 * Skills 的装配。
 *
 * <p>整块用 {@code agenttrail.skills.directory} 这个属性开关兜住：没配技能目录就等于
 * 没启用技能能力，连带数据库依赖和定时任务一起不加载。循环内核本身跑起来并不需要数据库，
 * 让一个可选能力去拉高整个应用的启动前提是不合适的。
 *
 * <p>这里刻意**不注册 {@code SkillsTool} 这个 bean**。技能工具是"每次对话请求现装一个"
 * 的东西，做成单例 bean 恰恰是踩坑点 #48 要避免的写法——请求侧应当调
 * {@link SkillManager#buildSkillsTool()}，拿到的是按当下启用状态新建的实例。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "agenttrail.skills", name = "directory")
public class SkillsConfiguration {

    @Bean
    public SkillRepository skillRepository(JdbcClient jdbcClient) {
        return new SkillRepository(jdbcClient);
    }

    @Bean
    public SkillManager skillManager(SkillRepository skillRepository,
                                     @Value("${agenttrail.skills.directory}") String skillsDirectory) {
        return new SkillManager(Path.of(skillsDirectory), skillRepository);
    }
}
