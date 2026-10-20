-- 交接令牌单次使用登记（H2 PostgreSQL 模式与 PostgreSQL 通用；每次启动执行，必须幂等）
CREATE TABLE IF NOT EXISTS aiw_handoff_jti (
    jti        VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS aiw_handoff_jti_expires_at_idx ON aiw_handoff_jti (expires_at);
