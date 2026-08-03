package com.agenttrail.sys.dto;

import com.agenttrail.sys.entity.SysUser;
import java.util.List;

/** 用户列表的分页事实，避免 Controller 直接处理 offset 计算。 */
public record SysUserPage(List<SysUser> items, long total, int page, int size) {
}
