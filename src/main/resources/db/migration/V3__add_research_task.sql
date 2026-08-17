-- 深度研究任务元信息（issue #108 / R20）。
--
-- 和 db/schema.sql 里那段建表保持一致：schema.sql 是本地/测试用的"每次启动幂等重跑全量"，
-- 这里是生产的版本化增量迁移，两边用途不同、不自动同步（见 schema.sql 开头 1-4 行）。
-- prompt_stamps 那次踩的坑就是只改了一边。
--
-- 只存元信息，不存报告正文——完成的报告随 CapabilityConversationService 落进
-- agent_session.timeline，在这里再存一份就是同一个产物存两处，两份必然漂移。

CREATE TABLE IF NOT EXISTS research_task
(
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，同时是对外的 taskId；自增保证重启后不复用编号',
    user_id          VARCHAR(100) NULL COMMENT '发起人，查询与取消都要做归属校验',
    conversation_id  VARCHAR(100) NOT NULL COMMENT '发起这个任务的会话标识',
    question         LONGTEXT     NOT NULL COMMENT '用户的原始提问，用于列表展示和排查',
    status           VARCHAR(20)  NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED / CANCELLED',
    error_msg        LONGTEXT     NULL COMMENT '失败原因；成功或进行中为 NULL',
    created_at       BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at       BIGINT       NOT NULL COMMENT '最近一次状态推进的时刻（epoch millis）',
    PRIMARY KEY (id),
    KEY idx_research_task_user (user_id),
    KEY idx_research_task_conversation (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '深度研究任务元信息（报告正文在 agent_session.timeline，不在这里）';
