package com.agenttrail.capability.deepresearch;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.BeforeAll;
import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #108 / R20：{@link JdbcResearchTaskRecordStore} 跑**真实 MySQL**，不用 H2
 * （同 {@code JdbcSessionStoreIT} 的规矩）。
 *
 * <p>行为契约的等价断言在 {@link ResearchTaskRecordStoreContractTest}（内存实现，每次
 * {@code mvn test} 都跑）。这里额外验的是只有真库才能验的东西：自增主键的不复用、
 * {@code AND status = 'RUNNING'} 这个并发保护在 SQL 层真的生效。
 */
class JdbcResearchTaskRecordStoreIT {

    private static DataSource dataSource;
    private JdbcResearchTaskRecordStore store;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE research_task").update();
        store = new JdbcResearchTaskRecordStore(dataSource);
    }

    @Test
    @DisplayName("建出来是 RUNNING，自增主键就是对外的 taskId")
    void createsRunningRowsKeyedByTheAutoIncrementId() {
        long id = store.create("u-1", "conv-1", "研究 Java 就业趋势");

        assertThat(id).isPositive();
        assertThat(store.find(id)).hasValueSatisfying(record -> {
            assertThat(record.isRunning()).isTrue();
            assertThat(record.userId()).isEqualTo("u-1");
            assertThat(record.conversationId()).isEqualTo("conv-1");
            assertThat(record.question()).isEqualTo("研究 Java 就业趋势");
            assertThat(record.errorMsg()).isNull();
            assertThat(record.createdAtMillis()).isPositive();
        });
    }

    /**
     * **这是换掉那个 {@code AtomicLong} 的理由。**进程内自增重启后从 0 重来，新任务会复用旧编号；
     * 数据库自增不会——即便中间的行被删掉，也不会有人拿到已经用过的 taskId。
     */
    @Test
    @DisplayName("taskId 不复用：删掉最后一条之后，新建的仍然往后排")
    void neverReusesATaskIdEvenAfterRowsAreRemoved() {
        long first = store.create("u-1", "conv-1", "第一个");
        long second = store.create("u-1", "conv-1", "第二个");
        JdbcClient.create(dataSource).sql("DELETE FROM research_task WHERE id = ?").param(second).update();

        long third = store.create("u-1", "conv-1", "第三个");

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    /**
     * worker 的完成事件和用户点取消可能几乎同时到达。{@code AND status = 'RUNNING'} 让
     * **先落地的那个才是真实原因**——没有它，一个已经 SUCCESS 的任务会被随后到达的取消请求
     * 改写成 CANCELLED，用户手里明明有报告，状态却说他取消了。
     */
    @Test
    @DisplayName("终态只写一次：后到的 UPDATE 在 SQL 层就被 status='RUNNING' 挡掉")
    void refusesToOverwriteATerminalStatusAtTheSqlLevel() {
        long id = store.create("u-1", "conv-1", "问题");

        store.markTerminal(id, DeepResearchTaskStatus.SUCCESS, null);
        store.markTerminal(id, DeepResearchTaskStatus.CANCELLED, "用户取消");

        assertThat(store.find(id)).hasValueSatisfying(record -> {
            assertThat(record.status()).isEqualTo(DeepResearchTaskStatus.SUCCESS);
            assertThat(record.errorMsg()).isNull();
        });
    }

    /** R20 的核心：启动扫描把上一个进程留下的 RUNNING 一律标成被打断，已终态的不动。 */
    @Test
    @DisplayName("启动扫描只改 RUNNING，返回受影响条数")
    void sweepsOnlyTheRowsLeftRunningByThePreviousProcess() {
        long stillRunning = store.create("u-1", "conv-1", "还在跑");
        long anotherRunning = store.create("u-2", "conv-2", "也在跑");
        long finished = store.create("u-1", "conv-1", "已跑完");
        store.markTerminal(finished, DeepResearchTaskStatus.SUCCESS, null);

        assertThat(store.markRunningAsInterrupted()).isEqualTo(2);

        for (long id : new long[] {stillRunning, anotherRunning}) {
            assertThat(store.find(id)).hasValueSatisfying(record -> {
                assertThat(record.status()).isEqualTo(DeepResearchTaskStatus.FAILED);
                assertThat(record.errorMsg()).isEqualTo(ResearchTaskRecord.INTERRUPTED_BY_RESTART);
            });
        }
        assertThat(store.find(finished)).hasValueSatisfying(record ->
                assertThat(record.status()).isEqualTo(DeepResearchTaskStatus.SUCCESS));
        assertThat(store.markRunningAsInterrupted()).as("再扫一次没有可改的").isZero();
    }

    @Test
    @DisplayName("进行中列表按用户隔离")
    void listsRunningTasksPerUser() {
        long mine = store.create("u-1", "conv-1", "我的");
        store.create("u-2", "conv-2", "别人的");
        long mineDone = store.create("u-1", "conv-1", "我的已完成");
        store.markTerminal(mineDone, DeepResearchTaskStatus.SUCCESS, null);

        assertThat(store.runningIdsFor("u-1")).containsExactly(mine);
    }

    @Test
    @DisplayName("查不到的 id 返回空")
    void returnsEmptyForUnknownIds() {
        assertThat(store.find(9_999_999L)).isEmpty();
    }
}
