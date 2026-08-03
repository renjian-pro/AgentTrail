package com.agenttrail.sys.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.sys.dto.DeptTreeNode;
import com.agenttrail.sys.service.SysDeptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SysDeptController {
    private final SysDeptService deptService;
    public SysDeptController(SysDeptService deptService) { this.deptService = deptService; }

    @GetMapping("/api/sys/depts")
    @SaCheckPermission("sys:dept:view")
    public List<DeptTreeNode> tree() { return deptService.tree(); }
}
