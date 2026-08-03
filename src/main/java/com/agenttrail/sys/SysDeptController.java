package com.agenttrail.sys;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.sys.dto.DeptTreeNode;
import com.agenttrail.sys.entity.SysDept;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SysDeptController {
    private final JdbcDeptStore deptStore;
    public SysDeptController(JdbcDeptStore deptStore) { this.deptStore = deptStore; }

    @GetMapping("/api/sys/depts")
    @SaCheckPermission("sys:dept:view")
    public List<DeptTreeNode> tree() {
        List<SysDept> rows = deptStore.findAll();
        Map<Long, DeptTreeNode> nodes = new LinkedHashMap<>();
        rows.forEach(row -> nodes.put(row.id(), new DeptTreeNode(row.id(), row.name(), row.parentId(), row.sort(), List.of())));
        Map<Long, List<DeptTreeNode>> children = new LinkedHashMap<>();
        for (SysDept row : rows) children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(nodes.get(row.id()));
        return children.getOrDefault(0L, List.of()).stream().map(node -> attach(node, children)).toList();
    }

    private DeptTreeNode attach(DeptTreeNode node, Map<Long, List<DeptTreeNode>> children) {
        return node.withChildren(children.getOrDefault(node.id(), List.of()).stream().map(child -> attach(child, children)).toList());
    }
}
