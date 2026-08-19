package com.agenttrail.capability.ppt;

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
import com.agenttrail.platform.error.RetryClass;

import static org.assertj.core.api.Assertions.assertThat;

/** 跑真实 MySQL（见 {@link SharedMySql}），不用 H2——和这个项目其它 {@code Jdbc*StoreIT} 同一条规矩。 */
class JdbcPptTaskStoreIT {

    private static DataSource dataSource;
    private JdbcPptTaskStore store;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE ppt_generation_idempotency").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE ppt_generation_stage_event").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE ppt_generation_task").update();
        store = new JdbcPptTaskStore(dataSource);
    }

    @Test
    void createsATaskWithInitStatusAndReturnsAGeneratedId() {
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT"));

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.INIT);
        assertThat(task.errorMsg()).isNull();
        assertThat(task.conversationId()).isEqualTo("conv-1");
        assertThat(task.createdAtMillis()).isPositive();
        assertThat(task.runStatus()).isEqualTo(PptRunStatus.QUEUED);
        assertThat(task.revision()).isZero();
        assertThat(task.contextVersion()).isEqualTo(PptGenerationContext.CURRENT_CONTEXT_VERSION);
    }

    @Test
    void conditionalAdvancePersistsRevisionAndStageEventAtomically() {
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT"));

        assertThat(store.conditionalAdvance(id, PptState.INIT, 0,
                PptState.CLARIFY, PptRunStatus.RUNNING,
                PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT"))).isTrue();
        assertThat(store.conditionalAdvance(id, PptState.INIT, 0,
                PptState.REQUIREMENT, PptRunStatus.RUNNING,
                PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT"))).isFalse();

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.CLARIFY);
        assertThat(task.revision()).isEqualTo(1);
        assertThat(task.attempt()).isEqualTo(1);
        assertThat(store.eventsForTask(id)).singleElement().satisfies(event -> {
            assertThat(event.stage()).isEqualTo(PptState.INIT);
            assertThat(event.outcome()).isEqualTo(PptCheckpointEvent.OUTCOME_SUCCEEDED);
            assertThat(event.revisionBefore()).isZero();
            assertThat(event.revisionAfter()).isEqualTo(1);
        });
    }

    @Test
    void idempotentCreationUsesTheDatabaseUniqueBinding() {
        PptGenerationContext context = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT");

        PptTaskCreation first = store.createIdempotent("user-1", "conv-1", context, "CREATE", "key-1");
        PptTaskCreation replay = store.createIdempotent("user-1", "conv-1", context, "CREATE", "key-1");

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.taskId()).isEqualTo(first.taskId());
    }

    @Test
    void claimAndRetryFailurePersistAttemptAndNextRetryTime() {
        long id = store.create("user-1", "conv-1", PptGenerationContext.initial("conv-1", "问题"));
        assertThat(store.claim(id, PptState.INIT, 0)).isTrue();
        PptFailure failure = new PptFailure("PPT_TIMEOUT", PptState.INIT, true,
                RetryClass.RETRIABLE, 1, "请求超时，请稍后重试", System.currentTimeMillis());
        long retryAt = System.currentTimeMillis() + 60_000;

        assertThat(store.recordFailureIfCurrent(id, PptState.INIT, 1, failure,
                PptRunStatus.RETRY_WAIT, retryAt)).isTrue();
        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.runStatus()).isEqualTo(PptRunStatus.RETRY_WAIT);
        assertThat(task.attempt()).isEqualTo(1);
        assertThat(task.nextRetryAtMillis()).isEqualTo(retryAt);
        assertThat(PptFailureJson.fromJson(task.failureJson()).code()).isEqualTo("PPT_TIMEOUT");
        assertThat(store.recoverableTaskIds(System.currentTimeMillis(), 10)).isEmpty();
        assertThat(store.recoverableTaskIds(retryAt, 10)).containsExactly(id);
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(store.findById(999_999L)).isEmpty();
    }

    @Test
    void advancePersistsTheNewStateAndContextAndClearsAnyPriorError() {
        long id = store.create("conv-1", PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT"));
        store.markFailed(id, PptState.REQUIREMENT, "之前失败过");

        PptGenerationContext withRequirement = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("标题", "主题", "受众", 3, "专业简洁"));
        store.advance(id, PptState.SEARCH, withRequirement);

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.SEARCH);
        assertThat(task.errorMsg()).as("成功推进要清空上一次失败的痕迹").isNull();
        PptGenerationContext restored = PptContextJson.fromJson(task.contextJson());
        assertThat(restored.requirement().title()).isEqualTo("标题");
    }

    @Test
    void findLatestByConversationIdReturnsTheMostRecentlyCreatedTaskForThatConversation() {
        long first = store.create("conv-1", PptGenerationContext.initial("conv-1", "第一次请求"));
        long second = store.create("conv-1", PptGenerationContext.initial("conv-1", "第二次请求"));
        store.create("conv-2", PptGenerationContext.initial("conv-2", "另一个会话的请求"));

        PptTask latest = store.findLatestByConversationId("conv-1").orElseThrow();

        assertThat(latest.id()).as("id 更大的那条（后创建的）才是最新").isEqualTo(second);
        assertThat(latest.id()).isNotEqualTo(first);
    }

    @Test
    void findLatestByConversationIdReturnsEmptyWhenThatConversationHasNoTask() {
        store.create("conv-1", PptGenerationContext.initial("conv-1", "问题"));

        assertThat(store.findLatestByConversationId("conv-从来没有过任务")).isEmpty();
    }

    @Test
    void markFailedKeepsStatusOnTheFailingStateAndRecordsTheErrorWithoutTouchingContext() {
        PptGenerationContext initialContext = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withSearchMaterials(List.of("素材"));
        long id = store.create("conv-1", initialContext);

        store.markFailed(id, PptState.OUTLINE, "模型输出不是合法 JSON");

        PptTask task = store.findById(id).orElseThrow();
        assertThat(task.status()).isEqualTo(PptState.OUTLINE);
        assertThat(task.errorMsg()).isEqualTo("模型输出不是合法 JSON");
        assertThat(task.runStatus()).isEqualTo(PptRunStatus.FAILED);
        assertThat(PptContextJson.fromJson(task.contextJson()).searchMaterials()).containsExactly("素材");
        assertThat(store.eventsForTask(id)).singleElement()
                .extracting(PptCheckpointEvent::outcome)
                .isEqualTo(PptCheckpointEvent.OUTCOME_FAILED);
    }
}
