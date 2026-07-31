package com.agenttrail.loop.memory;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 跑真实 MySQL（见 {@link SharedMySql}），不用 H2——和 {@code JdbcSessionStoreIT} 同一条规矩。 */
class JdbcMemoryStoreIT {

    private static DataSource dataSource;
    private JdbcMemoryStore store;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                SharedMySql.jdbcUrl(), SharedMySql.username(), SharedMySql.password());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        dataSource = source;
    }

    @BeforeEach
    void resetTable() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_memory").update();
        store = new JdbcMemoryStore(dataSource);
    }

    @Test
    void findByUserIdReturnsItemsInSaveOrder() {
        store.save(new MemoryItem("user-1", MemoryType.PROFILE, "产品经理", 1L));
        store.save(new MemoryItem("user-1", MemoryType.PREFERENCE, "偏好中文回复", 2L));

        List<MemoryItem> items = store.findByUserId("user-1");

        assertThat(items).extracting(MemoryItem::content).containsExactly("产品经理", "偏好中文回复");
    }

    @Test
    void keepsUsersIsolatedFromEachOther() {
        store.save(new MemoryItem("user-1", MemoryType.FACT, "使用 MySQL 8.0", 1L));
        store.save(new MemoryItem("user-2", MemoryType.FACT, "使用 PostgreSQL", 1L));

        assertThat(store.findByUserId("user-1")).extracting(MemoryItem::userId).containsExactly("user-1");
    }

    @Test
    void returnsNothingForAUserWithNoMemoryYet() {
        assertThat(store.findByUserId("never-seen")).isEmpty();
    }

    @Test
    void roundTripsAllFourMemoryTypes() {
        for (MemoryType type : MemoryType.values()) {
            store.save(new MemoryItem("user-1", type, "内容-" + type, 1L));
        }

        assertThat(store.findByUserId("user-1")).extracting(MemoryItem::type)
                .containsExactlyInAnyOrder(MemoryType.values());
    }
}
