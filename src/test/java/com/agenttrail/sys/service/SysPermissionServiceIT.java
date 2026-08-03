package com.agenttrail.sys.service;

import com.agenttrail.support.SharedMySql;
import com.agenttrail.sys.store.JdbcPermissionStore;
import com.agenttrail.sys.store.JdbcRoleStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 {@link SysPermissionServiceImpl#replace} 的 {@code @Transactional} 真的在起作用。
 *
 * <p>{@link JdbcPermissionStore#replaceRolePermissions} 内部是"先 DELETE 全部旧关联，
 * 再逐条 INSERT 新关联"——两条独立语句。没有事务包裹时，中途失败会留下"权限被清空但没插回
 * 完整新集合"这种半成品状态，是真实的数据损坏风险，不是理论问题。
 *
 * <p>用真实 MySQL（见 {@link SharedMySql}），靠 {@code permission_id} 列的 NOT NULL 约束
 * 制造一次插入中途失败——不为了测试专门在生产代码里开洞。
 */
class SysPermissionServiceIT {

    private static DataSource dataSource;
    private static SysPermissionService service;
    private static JdbcPermissionStore permissionStore;

    private long roleId;
    private long permissionOneId;
    private long permissionTwoId;

    @BeforeAll
    static void bootTransactionalContext() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                SharedMySql.jdbcUrl(), SharedMySql.username(), SharedMySql.password());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        dataSource = source;

        // 手动装配一个带 @EnableTransactionManagement 的最小上下文——这是唯一能验证
        // @Transactional 真的生效的方式：直接 new SysPermissionServiceImpl(...) 不会被
        // Spring AOP 代理包裹，事务边界根本不会创建。
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionalTestConfig.class);
        context.refresh();
        service = context.getBean(SysPermissionService.class);
        permissionStore = context.getBean(JdbcPermissionStore.class);
    }

    @BeforeEach
    void seedRoleAndPermissions() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM sys_role_permission").update();
        jdbc.sql("DELETE FROM sys_permission").update();
        jdbc.sql("DELETE FROM sys_role").update();

        roleId = insertRole(jdbc, "it_role_" + System.nanoTime());
        permissionOneId = insertPermission(jdbc, "it:one:" + System.nanoTime());
        permissionTwoId = insertPermission(jdbc, "it:two:" + System.nanoTime());
        permissionStore.replaceRolePermissions(roleId, List.of(permissionOneId));
    }

    @Test
    void replacePersistsTheNewSetOnSuccess() {
        List<Long> updated = service.replace(roleId, List.of(permissionTwoId)).stream()
                .map(p -> p.id()).toList();

        assertThat(updated).containsExactly(permissionTwoId);
    }

    @Test
    void replaceRollsBackBothTheDeleteAndThePartialInsertOnMidLoopFailure() {
        // null 排第二位：第一条 INSERT 成功，第二条因 permission_id 列 NOT NULL 失败，
        // 第三条永远不会执行——没有事务包裹的话，角色会变成"只剩 permissionTwoId"这种半成品。
        List<Long> withNullInTheMiddle = Arrays.asList(permissionTwoId, (Long) null);

        assertThatThrownBy(() -> service.replace(roleId, withNullInTheMiddle))
                .isInstanceOf(RuntimeException.class);

        List<Long> afterFailedAttempt = permissionStore.findByRoleId(roleId).stream().map(p -> p.id()).toList();
        assertThat(afterFailedAttempt)
                .as("失败的 replace 不能改变角色原有的权限集合")
                .containsExactly(permissionOneId);
    }

    private static long insertRole(JdbcClient jdbc, String code) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO sys_role (code, name, data_scope, sort, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'SELF', 0, 'ACTIVE', ?, ?)")
                .param(code).param(code).param(System.currentTimeMillis()).param(System.currentTimeMillis())
                .update(holder);
        return holder.getKey().longValue();
    }

    private static long insertPermission(JdbcClient jdbc, String code) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO sys_permission (code, name, module, created_at) VALUES (?, ?, 'test', ?)")
                .param(code).param(code).param(System.currentTimeMillis())
                .update(holder);
        return holder.getKey().longValue();
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionalTestConfig {
        @Bean
        DataSource dataSource() { return dataSource; }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcPermissionStore jdbcPermissionStore(DataSource dataSource) { return new JdbcPermissionStore(dataSource); }

        @Bean
        JdbcRoleStore jdbcRoleStore(DataSource dataSource) { return new JdbcRoleStore(dataSource); }

        @Bean
        SysPermissionService sysPermissionService(JdbcPermissionStore permissionStore, JdbcRoleStore roleStore) {
            return new SysPermissionServiceImpl(permissionStore, roleStore);
        }
    }
}
