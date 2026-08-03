package com.agenttrail.sys.service;

import com.agenttrail.sys.dto.DeptTreeNode;
import com.agenttrail.sys.entity.SysDept;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 树形组装是纯函数，不需要数据库——这正是把它从 Controller 挪进 Service 的价值之一。 */
class SysDeptServiceImplTest {

    @Test
    void groupsFlatRowsIntoATreeByParentId() {
        List<SysDept> rows = List.of(
                dept(100L, 0L, "集团"),
                dept(101L, 100L, "华东"),
                dept(102L, 100L, "华南"),
                dept(111L, 101L, "上海销售部"));

        List<DeptTreeNode> tree = SysDeptServiceImpl.buildTree(rows);

        assertThat(tree).hasSize(1);
        DeptTreeNode root = tree.get(0);
        assertThat(root.id()).isEqualTo(100L);
        assertThat(root.children()).extracting(DeptTreeNode::id).containsExactlyInAnyOrder(101L, 102L);
        DeptTreeNode east = root.children().stream().filter(n -> n.id().equals(101L)).findFirst().orElseThrow();
        assertThat(east.children()).extracting(DeptTreeNode::id).containsExactly(111L);
        assertThat(east.children().get(0).children()).isEmpty();
    }

    @Test
    void returnsEmptyListWhenNoRows() {
        assertThat(SysDeptServiceImpl.buildTree(List.of())).isEmpty();
    }

    private static SysDept dept(long id, long parentId, String name) {
        return new SysDept(id, name, parentId, "0", 0, "ACTIVE", 1L, 1L);
    }
}
