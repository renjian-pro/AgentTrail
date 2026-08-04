package com.agenttrail.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.auth.dto.LoginRequest;
import com.agenttrail.auth.dto.LoginResponse;
import com.agenttrail.auth.dto.UserInfo;
import com.agenttrail.auth.service.AuthService;
import com.agenttrail.sys.entity.SysUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 登录、登出、当前用户三个薄接口；token 通过 Sa-Token 标准响应头/JSON 字段返回。 */
@RestController
public class LoginController {
    private final AuthService authService;

    public LoginController(AuthService authService) { this.authService = authService; }

    @PostMapping("/api/auth/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String token = authService.login(request.username(), request.password());
        SysUser user = authService.currentUser().orElseThrow();
        return new LoginResponse(token, UserInfo.of(user, StpUtil.getRoleList()));
    }

    @PostMapping("/api/auth/logout")
    public void logout() { authService.logout(); }

    @GetMapping("/api/auth/info")
    public UserInfo info() {
        SysUser user = authService.currentUser().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效"));
        return UserInfo.of(user, StpUtil.getRoleList());
    }
}
