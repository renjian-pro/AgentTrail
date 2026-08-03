package com.agenttrail.sys.service;

import com.agenttrail.sys.dto.DeptTreeNode;

import java.util.List;

public interface SysDeptService {
    List<DeptTreeNode> tree();
}
