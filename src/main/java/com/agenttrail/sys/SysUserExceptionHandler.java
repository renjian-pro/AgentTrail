package com.agenttrail.sys;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class SysUserExceptionHandler {
    @ExceptionHandler(SysUserBusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> business(SysUserBusinessException failure) {
        return Map.of("code", "SYS_USER_OPERATION_REJECTED", "message", failure.getMessage());
    }
}
