package com.agenttrail.auth;

import com.agenttrail.sys.JdbcDeptStore;
import com.agenttrail.sys.JdbcPermissionStore;
import com.agenttrail.sys.JdbcRoleStore;
import com.agenttrail.sys.JdbcUserStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 身份领域的 JDBC Store 装配集中在 auth 边界，避免业务 Controller 自己 new 存储对象。 */
@Configuration
public class AuthConfiguration {
    @Bean public JdbcUserStore jdbcUserStore(DataSource dataSource) { return new JdbcUserStore(dataSource); }
    @Bean public JdbcRoleStore jdbcRoleStore(DataSource dataSource) { return new JdbcRoleStore(dataSource); }
    @Bean public JdbcDeptStore jdbcDeptStore(DataSource dataSource) { return new JdbcDeptStore(dataSource); }
    @Bean public JdbcPermissionStore jdbcPermissionStore(DataSource dataSource) { return new JdbcPermissionStore(dataSource); }
}
