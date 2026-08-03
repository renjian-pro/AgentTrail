package com.agenttrail.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 认证失败不区分用户名不存在与密码错误，统一返回 401。 */
@RestControllerAdvice
public class InvalidCredentialsExceptionHandler {
    @ExceptionHandler(AuthServiceImpl.InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> invalidCredentials(AuthServiceImpl.InvalidCredentialsException failure) {
        return Map.of("code", "AUTH_INVALID_CREDENTIALS", "message", failure.getMessage());
    }
}
