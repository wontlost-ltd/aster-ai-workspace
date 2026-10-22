-- 交接令牌单次使用登记（H2 PostgreSQL 模式与 PostgreSQL 通用；每次启动执行，必须幂等）
CREATE TABLE IF NOT EXISTS aiw_handoff_jti (
    jti        VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS aiw_handoff_jti_expires_at_idx ON aiw_handoff_jti (expires_at);

-- cloud → 本服务内部调用（GDPR 导出 / 清除）的 nonce 单次使用登记；过期行由定时任务清理
CREATE TABLE IF NOT EXISTS aiw_internal_nonce (
    nonce      VARCHAR(32) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS aiw_internal_nonce_expires_at_idx ON aiw_internal_nonce (expires_at);
