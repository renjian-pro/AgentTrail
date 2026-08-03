package com.agenttrail.sys.dto;

import java.util.ArrayList;
import java.util.List;

public record DeptTreeNode(Long id, String name, Long parentId, int sort, List<DeptTreeNode> children) {
    public DeptTreeNode withChildren(List<DeptTreeNode> value) {
        return new DeptTreeNode(id, name, parentId, sort, List.copyOf(value));
    }
}
