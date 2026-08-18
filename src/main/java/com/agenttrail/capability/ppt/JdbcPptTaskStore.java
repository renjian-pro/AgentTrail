package com.agenttrail.capability.ppt;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@link PptTaskStore} 的 JDBC 落地实现——表结构见 {@code db/schema.sql} 的
 * {@code ppt_generation_task} 表。
 *
 * <p>条件推进和失败记录都在同一个事务里完成：单行 UPDATE 先校验 expected state/revision，
 * 命中后再追加阶段事件。这样即使多个 worker 误拿到同一任务，迟到提交也只能返回冲突，不能覆盖
 * 新快照；{@link PptGenerationService#run(long)} 的租约只负责减少重复执行，revision 才负责最终保护。
 */
public class JdbcPptTaskStore implements PptTaskStore {

    private static final String INSERT_SQL = """
            INSERT INTO ppt_generation_task
                (user_id, conversation_id, status, run_status, error_msg, cancel_requested,
                 context_version, revision, context_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, NULL, FALSE, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT id, user_id, conversation_id, status, run_status, error_msg, context_version, revision,
                   context_json, created_at, updated_at
            FROM ppt_generation_task
            WHERE id = ?
            """;

    // 按 id 倒序取第一条即"最新"——id 自增，比 created_at 更可靠（同毫秒内可能有并列），
    // 和 InMemoryPptTaskStore#findLatestByConversationId 用同一个排序口径，两个实现行为一致。
    private static final String SELECT_LATEST_BY_CONVERSATION_SQL = """
            SELECT id, user_id, conversation_id, status, run_status, error_msg, context_version, revision,
                   context_json, created_at, updated_at
            FROM ppt_generation_task
            WHERE conversation_id = ?
            ORDER BY id DESC
            LIMIT 1
            """;

    private static final String CONDITIONAL_ADVANCE_SQL = """
            UPDATE ppt_generation_task
            SET status = ?, run_status = ?, error_msg = NULL, context_version = ?, revision = revision + 1,
                context_json = ?, updated_at = ?
            WHERE id = ? AND status = ? AND revision = ?
            """;

    private static final String CONDITIONAL_MARK_FAILED_SQL = """
            UPDATE ppt_generation_task
            SET status = ?, run_status = ?, error_msg = ?, revision = revision + 1, updated_at = ?
            WHERE id = ? AND status = ? AND revision = ?
            """;

    private static final String REQUEST_CANCEL_SQL = """
            UPDATE ppt_generation_task
            SET cancel_requested = CASE
                    WHEN status IN ('SUCCESS', 'CANCELLED') THEN cancel_requested
                    ELSE TRUE END,
                run_status = CASE
                    WHEN status IN ('SUCCESS', 'CANCELLED') THEN run_status
                    ELSE 'CANCEL_REQUESTED' END,
                revision = CASE
                    WHEN status IN ('SUCCESS', 'CANCELLED') OR run_status = 'CANCEL_REQUESTED' THEN revision
                    ELSE revision + 1 END,
                updated_at = ?
            WHERE id = ?
            """;

    private static final String MARK_CANCELLED_SQL = """
            UPDATE ppt_generation_task
            SET status = ?, run_status = ?, error_msg = NULL, cancel_requested = FALSE,
                revision = revision + 1, updated_at = ?
            WHERE id = ?
            """;

    private static final String IS_CANCEL_REQUESTED_SQL = """
            SELECT cancel_requested FROM ppt_generation_task WHERE id = ?
            """;

    private static final String RUNNING_TASK_IDS_SQL = """
            SELECT id FROM ppt_generation_task
            WHERE user_id = ? AND run_status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
            ORDER BY id
            """;

    private static final String SELECT_EVENT_ATTEMPT_SQL = """
            SELECT COUNT(*) FROM ppt_generation_stage_event WHERE task_id = ? AND stage = ?
            """;

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO ppt_generation_stage_event
                (task_id, stage, attempt, started_at, finished_at, outcome, input_summary, output_summary,
                 error_code, retry_class, warning_code, worker_id, prompt_version, revision_before, revision_after)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_EVENTS_SQL = """
            SELECT task_id, stage, attempt, started_at, finished_at, outcome, input_summary, output_summary,
                   error_code, retry_class, warning_code, worker_id, prompt_version, revision_before, revision_after
            FROM ppt_generation_stage_event
            WHERE task_id = ?
            ORDER BY id ASC
            """;

    private static final String SELECT_TASK_STATE_SQL = """
            SELECT status, revision FROM ppt_generation_task WHERE id = ? FOR UPDATE
            """;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public JdbcPptTaskStore(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public long create(String userId, String conversationId, PptGenerationContext initialContext) {
        long now = System.currentTimeMillis();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql(INSERT_SQL)
                .param(userId)
                .param(conversationId)
                .param(PptState.INIT.name())
                .param(PptRunStatus.QUEUED.name())
                .param(initialContext.contextVersion())
                .param(0L)
                .param(PptContextJson.toJson(initialContext))
                .param(now)
                .param(now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<PptTask> findById(long id) {
        return jdbcClient.sql(SELECT_BY_ID_SQL)
                .param(id)
                .query(JdbcPptTaskStore::mapRow)
                .optional();
    }

    @Override
    public Optional<PptTask> findLatestByConversationId(String conversationId) {
        return jdbcClient.sql(SELECT_LATEST_BY_CONVERSATION_SQL)
                .param(conversationId)
                .query(JdbcPptTaskStore::mapRow)
                .optional();
    }

    @Override
    public boolean conditionalAdvance(long id, PptState expectedState, long expectedRevision,
            PptState newState, PptRunStatus newRunStatus, PptGenerationContext context) {
        Boolean updated = transactionTemplate.execute(status -> {
            long now = System.currentTimeMillis();
            int rows = jdbcClient.sql(CONDITIONAL_ADVANCE_SQL)
                    .param(newState.name())
                    .param(newRunStatus.name())
                    .param(context.contextVersion())
                    .param(PptContextJson.toJson(context))
                    .param(now)
                    .param(id)
                    .param(expectedState.name())
                    .param(expectedRevision)
                    .update();
            if (rows == 0) {
                return false;
            }
            insertEvent(id, expectedState, PptCheckpointEvent.OUTCOME_SUCCEEDED, now, now,
                    null, expectedRevision + 1);
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public boolean markFailedIfCurrent(long id, PptState expectedState, long expectedRevision,
            PptState failedState, String errorMsg) {
        Boolean updated = transactionTemplate.execute(status -> {
            long now = System.currentTimeMillis();
            int rows = jdbcClient.sql(CONDITIONAL_MARK_FAILED_SQL)
                    .param(failedState.name())
                    .param(PptRunStatus.FAILED.name())
                    .param(errorMsg)
                    .param(now)
                    .param(id)
                    .param(expectedState.name())
                    .param(expectedRevision)
                    .update();
            if (rows == 0) {
                return false;
            }
            insertEvent(id, failedState, PptCheckpointEvent.OUTCOME_FAILED, now, now,
                    errorMsg, expectedRevision + 1);
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public List<PptCheckpointEvent> eventsForTask(long taskId) {
        return jdbcClient.sql(SELECT_EVENTS_SQL)
                .param(taskId)
                .query((rs, rowNum) -> new PptCheckpointEvent(
                        rs.getLong("task_id"),
                        PptState.valueOf(rs.getString("stage")),
                        rs.getInt("attempt"),
                        rs.getLong("started_at"),
                        rs.getLong("finished_at"),
                        rs.getString("outcome"),
                        rs.getString("input_summary"),
                        rs.getString("output_summary"),
                        rs.getString("error_code"),
                        rs.getString("retry_class"),
                        rs.getString("warning_code"),
                        rs.getString("worker_id"),
                        rs.getString("prompt_version"),
                        rs.getLong("revision_before"),
                        rs.getLong("revision_after")))
                .list();
    }

    @Override
    public void requestCancel(long id) {
        int updated = jdbcClient.sql(REQUEST_CANCEL_SQL)
                .param(System.currentTimeMillis())
                .param(id)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("PPT 任务不存在: " + id);
        }
    }

    @Override
    public void markCancelled(long id, PptState atState) {
        transactionTemplate.executeWithoutResult(status -> {
            var current = jdbcClient.sql(SELECT_TASK_STATE_SQL)
                    .param(id)
                    .query((rs, rowNum) -> new TaskState(PptState.valueOf(rs.getString("status")), rs.getLong("revision")))
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("PPT 任务不存在: " + id));
            if (current.state() == PptState.SUCCESS || current.state() == PptState.CANCELLED) {
                return;
            }
            long now = System.currentTimeMillis();
            int updated = jdbcClient.sql(MARK_CANCELLED_SQL)
                    .param(PptState.CANCELLED.name())
                    .param(PptRunStatus.CANCELLED.name())
                    .param(now)
                    .param(id)
                    .update();
            if (updated == 0) {
                throw new PptCheckpointConflictException(id, current.state(), current.revision());
            }
            insertEvent(id, atState, PptCheckpointEvent.OUTCOME_CANCELLED, now, now,
                    null, current.revision() + 1);
        });
    }

    @Override
    public boolean isCancelRequested(long id) {
        return jdbcClient.sql(IS_CANCEL_REQUESTED_SQL)
                .param(id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    @Override
    public List<Long> runningTaskIdsFor(String userId) {
        if (userId == null) {
            return List.of();
        }
        return jdbcClient.sql(RUNNING_TASK_IDS_SQL)
                .param(userId)
                .query(Long.class)
                .list();
    }

    private static PptTask mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PptTask(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                PptState.valueOf(rs.getString("status")),
                PptRunStatus.valueOf(rs.getString("run_status")),
                rs.getString("error_msg"),
                rs.getString("context_json"),
                rs.getInt("context_version"),
                rs.getLong("revision"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private void insertEvent(long taskId, PptState stage, String outcome, long startedAt, long finishedAt,
            String errorMessage, long revisionAfter) {
        int attempt = jdbcClient.sql(SELECT_EVENT_ATTEMPT_SQL)
                .param(taskId)
                .param(stage.name())
                .query(Integer.class)
                .single() + 1;
        jdbcClient.sql(INSERT_EVENT_SQL)
                .param(taskId)
                .param(stage.name())
                .param(attempt)
                .param(startedAt)
                .param(finishedAt)
                .param(outcome)
                .param(null)
                .param(null)
                .param(errorMessage == null ? null : "PPT_STAGE_FAILED")
                .param(null)
                .param(null)
                .param(null)
                .param(null)
                .param(revisionAfter - 1)
                .param(revisionAfter)
                .update();
    }

    private record TaskState(PptState state, long revision) {
    }
}
