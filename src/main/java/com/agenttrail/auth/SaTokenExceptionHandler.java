package com.agenttrail.auth;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把认证框架异常收敛成前端稳定可识别的 HTTP 语义。 */
@RestControllerAdvice
public class SaTokenExceptionHandler {
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> notLogin(NotLoginException failure) {
        return Map.of("code", "AUTH_REQUIRED", "message", "未登录或登录已失效");
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden(RuntimeException failure) {
        return Map.of("code", "AUTH_FORBIDDEN", "message", "没有执行该操作的权限");
    }
}
