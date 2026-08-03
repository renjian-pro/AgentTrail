package com.agenttrail.sys.service;

import com.agenttrail.sys.dto.SysUserPage;
import com.agenttrail.sys.dto.UserCreateRequest;
import com.agenttrail.sys.dto.UserUpdateRequest;
import com.agenttrail.sys.entity.SysUser;

public interface SysUserService {
    SysUserPage search(String keyword, int page, int size);
    SysUser create(UserCreateRequest request);
    SysUser update(long id, UserUpdateRequest request);
    void updateStatus(long id, String status);
    void delete(long id);
}
