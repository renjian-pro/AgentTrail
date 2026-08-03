package com.agenttrail.sys.service;

import com.agenttrail.sys.dto.DeptTreeNode;
import com.agenttrail.sys.entity.SysDept;
import com.agenttrail.sys.store.JdbcDeptStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 平铺部门列表转树形结构的组装逻辑——业务转换，不是 HTTP 关注点，不留在 Controller 里。 */
@Service
public class SysDeptServiceImpl implements SysDeptService {
    private final JdbcDeptStore deptStore;

    public SysDeptServiceImpl(JdbcDeptStore deptStore) { this.deptStore = deptStore; }

    @Override
    public List<DeptTreeNode> tree() { return buildTree(deptStore.findAll()); }

    /** 纯函数，不依赖数据库，方便单独做单元测试（见 {@link SysDeptServiceImplTest}）。 */
    static List<DeptTreeNode> buildTree(List<SysDept> rows) {
        Map<Long, DeptTreeNode> nodes = new LinkedHashMap<>();
        rows.forEach(row -> nodes.put(row.id(), new DeptTreeNode(row.id(), row.name(), row.parentId(), row.sort(), List.of())));
        Map<Long, List<DeptTreeNode>> children = new LinkedHashMap<>();
        for (SysDept row : rows) children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(nodes.get(row.id()));
        return children.getOrDefault(0L, List.of()).stream().map(node -> attach(node, children)).toList();
    }

    private static DeptTreeNode attach(DeptTreeNode node, Map<Long, List<DeptTreeNode>> children) {
        return node.withChildren(children.getOrDefault(node.id(), List.of()).stream().map(child -> attach(child, children)).toList());
    }
}
