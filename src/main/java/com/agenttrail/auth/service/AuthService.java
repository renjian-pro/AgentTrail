package com.agenttrail.auth.service;

import com.agenttrail.sys.entity.SysUser;
import java.util.Optional;

/** 登录会话的唯一入口，Controller 不直接操作 Sa-Token。 */
public interface AuthService {
    String login(String username, String password);
    void logout();
    Optional<SysUser> currentUser();
}
