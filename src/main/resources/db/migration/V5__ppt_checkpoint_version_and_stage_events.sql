-- PPT checkpoint 版本化（R1-R5）：业务阶段 status 与运行生命周期 run_status 分离，
-- revision 用于阻止迟到 worker 覆盖新快照，context_version 用于快照迁移。
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
       AND COLUMN_NAME = 'run_status') = 0,
    'ALTER TABLE ppt_generation_task ADD COLUMN run_status VARCHAR(20) NOT NULL DEFAULT ''QUEUED'' COMMENT ''运行生命周期，与 status 业务 checkpoint 分离'' AFTER status',
    'DO 0');
PREPARE add_ppt_run_status FROM @ddl;
EXECUTE add_ppt_run_status;
DEALLOCATE PREPARE add_ppt_run_status;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
       AND COLUMN_NAME = 'context_version') = 0,
    'ALTER TABLE ppt_generation_task ADD COLUMN context_version INT NOT NULL DEFAULT 1 COMMENT ''上下文快照版本，用于迁移与兼容读取'' AFTER cancel_requested',
    'DO 0');
PREPARE add_ppt_context_version FROM @ddl;
EXECUTE add_ppt_context_version;
DEALLOCATE PREPARE add_ppt_context_version;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ppt_generation_task'
       AND COLUMN_NAME = 'revision') = 0,
    'ALTER TABLE ppt_generation_task ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT ''单调递增 checkpoint 版本，防止迟到 worker 覆盖新快照'' AFTER context_version',
    'DO 0');
PREPARE add_ppt_revision FROM @ddl;
EXECUTE add_ppt_revision;
DEALLOCATE PREPARE add_ppt_revision;

-- 将旧版本任务的业务 status 映射到新增的运行生命周期字段。revision=0
-- 只覆盖尚未经过新 checkpoint 逻辑推进的历史行，避免重写新版本任务。
UPDATE ppt_generation_task
SET run_status = CASE
    WHEN status = 'SUCCESS' THEN 'SUCCEEDED'
    WHEN status = 'CANCELLED' THEN 'CANCELLED'
    WHEN status = 'AWAITING_INPUT' THEN 'WAITING_INPUT'
    WHEN error_msg IS NOT NULL THEN 'FAILED'
    ELSE 'QUEUED'
END
WHERE revision = 0;

CREATE TABLE IF NOT EXISTS ppt_generation_stage_event
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '阶段事件主键，追加写入不覆盖',
    task_id         BIGINT       NOT NULL COMMENT 'PPT 任务 ID',
    stage           VARCHAR(20)  NOT NULL COMMENT '发生事件的业务阶段',
    attempt         INT          NOT NULL COMMENT '该阶段第几次执行',
    started_at      BIGINT       NOT NULL COMMENT '阶段开始时刻（epoch millis）',
    finished_at     BIGINT       NOT NULL COMMENT '阶段结束时刻（epoch millis）',
    outcome         VARCHAR(20)  NOT NULL COMMENT 'SUCCEEDED/FAILED/CANCELLED 等结果',
    input_summary   TEXT         NULL COMMENT '脱敏后的输入摘要，不保存完整思考链',
    output_summary  TEXT         NULL COMMENT '脱敏后的输出摘要，不保存完整思考链',
    error_code      VARCHAR(100) NULL COMMENT '稳定错误码',
    retry_class     VARCHAR(30)  NULL COMMENT 'RETRIABLE/FATAL/DEGRADED/CANCELLED',
    warning_code    VARCHAR(100) NULL COMMENT '降级或质量警告码',
    worker_id       VARCHAR(100) NULL COMMENT '执行 worker 标识',
    prompt_version  VARCHAR(100) NULL COMMENT '提示词版本标识',
    revision_before BIGINT       NOT NULL COMMENT '提交前任务 revision',
    revision_after  BIGINT       NOT NULL COMMENT '提交后任务 revision',
    PRIMARY KEY (id),
    KEY idx_ppt_stage_event_task (task_id, id),
    KEY idx_ppt_stage_event_stage (task_id, stage, attempt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'PPT 生成阶段执行审计事件';
