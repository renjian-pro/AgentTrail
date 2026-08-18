package com.agenttrail.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.util.Map;

/** 为需要验证真实 MySQL 行为的集成测试提供一致的容器和建库入口。 */
public abstract class MySqlContainerTestSupport {

    @Container
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("agenttrail")
            .withUsername("agenttrail")
            .withPassword("agenttrail")
            // 集成测试不需要保留数据目录；tmpfs 避免 Docker Desktop 慢盘把每次 initdb 拉到分钟级。
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));

    protected static DataSource createDataSourceAndSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        return source;
    }
}
