package com.agenttrail.web.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.evaluation.GoldenCaseRequest;
import com.agenttrail.evaluation.GoldenCaseService;
import com.agenttrail.evaluation.GoldenCaseView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Golden Case 管理：YAML 内建用例只读展示，{@code golden_case} 表里的用例支持增删改——
 * 鉴权沿用既有的 evaluation 路由策略（登录即可，见 {@link GoldenEvaluationController}），
 * 前端只对 admin 角色开放这个页面入口。
 */
@RestController
public class GoldenCaseController {
    private final GoldenCaseService caseService;

    public GoldenCaseController(GoldenCaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping("/agent/v1/evaluation/cases")
    @SaCheckPermission("golden:case:view")
    public List<GoldenCaseView> list() {
        return caseService.listAll();
    }

    @PostMapping("/agent/v1/evaluation/cases")
    @SaCheckPermission("golden:case:create")
    public GoldenCaseView create(@RequestBody GoldenCaseRequest request) {
        return caseService.create(request);
    }

    @PutMapping("/agent/v1/evaluation/cases/{id}")
    @SaCheckPermission("golden:case:update")
    public GoldenCaseView update(@PathVariable String id, @RequestBody GoldenCaseRequest request) {
        return caseService.update(id, request);
    }

    @DeleteMapping("/agent/v1/evaluation/cases/{id}")
    @SaCheckPermission("golden:case:delete")
    public void delete(@PathVariable String id) {
        caseService.delete(id);
    }
}
