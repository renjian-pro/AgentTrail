-- agent_trace 补 prompt_stamps 列（issue #98 那批提示词版本戳落 trace 的配套迁移）。
--
-- 为什么需要单独一版而不是改 V1：V1 是"引入 Flyway 那一刻 schema.sql 的快照"，对已经
-- baseline 过的库而言它是**已应用**状态，往里加列不会被重新执行——列因此永远不会出现。
-- 2026-08-16 就是这么挂的：JdbcTraceStore 的 INSERT 带上了 prompt_stamps，而线上/本地的
-- agent_trace 里根本没有这一列，每一轮 trace 落库都 BadSqlGrammarException。
--
-- 写成条件式而不是裸 ALTER：新装的库走 V1 时已经带上了这一列（V1 里就有），到这里必须是空转，
-- 否则 Duplicate column name 会让整个 migrate 失败。MySQL 没有 ADD COLUMN IF NOT EXISTS。
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_trace' AND COLUMN_NAME = 'prompt_stamps') = 0,
    'ALTER TABLE agent_trace ADD COLUMN prompt_stamps VARCHAR(512) NULL COMMENT ''本轮用到的外置提示词标识 id@version#hash，多个逗号分隔''',
    'DO 0');
PREPARE add_prompt_stamps FROM @ddl;
EXECUTE add_prompt_stamps;
DEALLOCATE PREPARE add_prompt_stamps;
