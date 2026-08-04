package com.agenttrail.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.sys.store.JdbcUserStore;
import com.agenttrail.sys.entity.SysUser;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 用户名密码认证；无论用户名是否存在都返回同一错误，避免账号枚举。 */
@Service
public class AuthServiceImpl implements AuthService {
    private static final String INVALID_CREDENTIALS = "用户名或密码错误";

    private final JdbcUserStore userStore;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthServiceImpl(JdbcUserStore userStore, BCryptPasswordEncoder passwordEncoder) {
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String login(String username, String password) {
        Optional<SysUser> user = username == null || password == null
                ? Optional.empty() : userStore.findByUsername(username.trim());
        if (user.isEmpty() || !user.get().active() || !passwordEncoder.matches(password, user.get().password())) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS);
        }
        StpUtil.login(user.get().id());
        return StpUtil.getTokenValue();
    }

    @Override
    public void logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
    }

    @Override
    public Optional<SysUser> currentUser() {
        if (!StpUtil.isLogin()) return Optional.empty();
        long id = StpUtil.getLoginIdAsLong();
        return userStore.findById(id).filter(SysUser::active);
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException(String message) { super(message); }
    }
}
