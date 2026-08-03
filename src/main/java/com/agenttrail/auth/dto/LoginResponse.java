package com.agenttrail.auth.dto;

public record LoginResponse(String token, UserInfo user) {
}
