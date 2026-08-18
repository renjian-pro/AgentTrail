-- 高风险工具审批恢复的跨进程去重记录。
-- idempotency_key 由“工具名 + 会话 id + tool_call id”组成；唯一键让多个实例只能有一个执行者。
CREATE TABLE IF NOT EXISTS agent_tool_idempotency
(
    idempotency_key VARCHAR(300) NOT NULL COMMENT '工具名、会话与 tool_call 组成的幂等键',
    owner_token     CHAR(36)     NOT NULL COMMENT '当前租约所有者；迟到执行者不能完成或释放新租约',
    status          VARCHAR(20)  NOT NULL COMMENT 'IN_FLIGHT 或 COMPLETED',
    result          LONGTEXT     NULL COMMENT '首次成功执行的工具结果，重复恢复时直接回放',
    expires_at      DATETIME(6)  NOT NULL COMMENT '执行租约或已完成记录的过期时刻',
    updated_at      DATETIME(6)  NOT NULL COMMENT '最后状态变更时刻',
    PRIMARY KEY (idempotency_key),
    KEY idx_tool_idempotency_expiry (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '工具调用跨进程幂等记录';
