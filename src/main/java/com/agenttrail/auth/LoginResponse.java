package com.agenttrail.auth;

public record LoginResponse(String token, UserInfo user) {
}
