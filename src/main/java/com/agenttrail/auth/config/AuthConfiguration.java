package com.agenttrail.auth.config;

import com.agenttrail.sys.store.JdbcDeptStore;
import com.agenttrail.sys.store.JdbcPermissionStore;
import com.agenttrail.sys.store.JdbcRoleStore;
import com.agenttrail.sys.store.JdbcUserStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;

/** 身份领域的 JDBC Store 装配集中在 auth 边界，避免业务 Controller 自己 new 存储对象。 */
@Configuration
public class AuthConfiguration {
    @Bean public JdbcUserStore jdbcUserStore(@Qualifier("dataSource") DataSource dataSource) { return new JdbcUserStore(dataSource); }
    @Bean public JdbcRoleStore jdbcRoleStore(@Qualifier("dataSource") DataSource dataSource) { return new JdbcRoleStore(dataSource); }
    @Bean public JdbcDeptStore jdbcDeptStore(@Qualifier("dataSource") DataSource dataSource) { return new JdbcDeptStore(dataSource); }
    @Bean public JdbcPermissionStore jdbcPermissionStore(@Qualifier("dataSource") DataSource dataSource) { return new JdbcPermissionStore(dataSource); }
}
