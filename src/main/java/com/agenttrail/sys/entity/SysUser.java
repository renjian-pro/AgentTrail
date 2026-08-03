package com.agenttrail.sys.entity;

/** 用户身份事实；password 只在服务层使用，任何 HTTP 响应都必须转换为 UserVO。 */
public record SysUser(Long id, String username, String password, String nickname, String status,
        long createdAt, long updatedAt) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
