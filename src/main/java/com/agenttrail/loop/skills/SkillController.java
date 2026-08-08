package com.agenttrail.loop.skills;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 技能的运营管理端点。{@link SkillManager} 本身早就支持查看和启停，缺的只是这一层 HTTP 入口——
 * 没有它，"运营点开关下一轮立刻生效"这套设计就没有入口，只能手动改数据库。
 *
 * <p>不用 {@code @Component} 走扫描注册，而是作为 {@code @Bean} 挂在
 * {@link SkillsConfiguration} 里，跟 {@link SkillManager} 共用同一个
 * {@code agenttrail.skills.directory} 开关：技能能力没配置目录时，这组端点也不该存在。
 */
@RestController
public class SkillController {

    private final SkillManager skillManager;

    public SkillController(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @GetMapping("/api/sys/skills")
    @SaCheckPermission("skill:view")
    public List<SkillMetadata> list() {
        return skillManager.list();
    }

    /** 技能名或启用状态非法时，{@link SkillManager#setEnabled} 抛的 IllegalArgumentException 转成 400。 */
    @PatchMapping("/api/sys/skills/{name}/enabled")
    @SaCheckPermission("skill:manage-status")
    public void updateEnabled(@PathVariable String name, @RequestBody SkillEnabledRequest request) {
        try {
            skillManager.setEnabled(name, request.enabled());
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage(), failure);
        }
    }
}
