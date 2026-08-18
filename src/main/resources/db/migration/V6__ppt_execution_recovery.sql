-- PPT 执行恢复元数据（R6-R13）：失败/重试信息必须与业务 checkpoint 一起持久化，
-- 幂等键必须由数据库唯一约束保护，不能只存在单机内存。
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'ppt_generation_task' AND COLUMN_NAME = 'failure_json') = 0,
    'ALTER TABLE ppt_generation_task ADD COLUMN failure_json LONGTEXT NULL COMMENT ''脱敏结构化失败对象 JSON，兼容 error_msg 文本'' AFTER revision',
    'DO 0');
PREPARE add_ppt_failure_json FROM @ddl;
EXECUTE add_ppt_failure_json;
DEALLOCATE PREPARE add_ppt_failure_json;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'ppt_generation_task' AND COLUMN_NAME = 'warnings_json') = 0,
    'ALTER TABLE ppt_generation_task ADD COLUMN warnings_json LONGTEXT NULL COMMENT ''成功但降级时的 warning 列表 JSON'' AFTER failure_json',
    'DO 0');
PREPARE add_ppt_warnings_json FROM @ddl;
EXECUTE add_ppt_warnings_json;
DEALLOCATE PREPARE add_ppt_warnings_json;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'ppt_generation_task' AND COLUMN_NAME = 'attempt') = 0,
    'ALTER TABLE ppt_generation_task ADD COLUMN attempt INT NOT NULL DEFAULT 0 COMMENT ''当前 pipelineState 的执行次数'' AFTER warnings_json',
    'DO 0');
PREPARE add_ppt_attempt FROM @ddl;
EXECUTE add_ppt_attempt;
DEALLOCATE PREPARE add_ppt_attempt;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'ppt_generation_task' AND COLUMN_NAME = 'next_retry_at') = 0,
    'ALTER TABLE ppt_generation_task ADD COLUMN next_retry_at BIGINT NOT NULL DEFAULT 0 COMMENT ''RETRY_WAIT 最早可重试时刻（epoch millis）'' AFTER attempt',
    'DO 0');
PREPARE add_ppt_next_retry_at FROM @ddl;
EXECUTE add_ppt_next_retry_at;
DEALLOCATE PREPARE add_ppt_next_retry_at;

CREATE TABLE IF NOT EXISTS ppt_generation_idempotency
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '幂等绑定主键',
    user_id         VARCHAR(100) NOT NULL COMMENT '用户作用域，匿名请求使用 legacy',
    scope           VARCHAR(32)  NOT NULL COMMENT 'CREATE/MODIFY/RESUME 等作用域',
    idempotency_key VARCHAR(255) NOT NULL COMMENT '客户端生成的稳定幂等键',
    task_id         BIGINT       NOT NULL COMMENT '幂等键对应的 PPT 任务',
    created_at      BIGINT       NOT NULL COMMENT '绑定创建时刻（epoch millis）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ppt_idempotency (user_id, scope, idempotency_key),
    KEY idx_ppt_idempotency_task (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'PPT 创建/修改/恢复请求幂等绑定';
