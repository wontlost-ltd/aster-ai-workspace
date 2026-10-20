package com.wontlost.aster.aiw.auth;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 交接令牌的单次使用登记（表 aiw_handoff_jti，由 schema-aiw.sql 建表）。
 *
 * <p>以主键冲突实现原子的「先到先得」：多实例并发提交同一 jti 时只有一个 INSERT 成功。
 * 过期的 jti 可以安全清理——对应令牌本身已因 exp 被拒绝，不可能再被重放成功。
 */
@Component
public class HandoffJtiStore {
    private final JdbcTemplate jdbc;

    public HandoffJtiStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记 jti；首次返回 true，已被使用返回 false。数据库故障照常抛出（失败即拒绝交接）。 */
    public boolean claim(String jti, Instant expiresAt) {
        try {
            jdbc.update("insert into aiw_handoff_jti (jti, expires_at) values (?, ?)", jti, Timestamp.from(expiresAt));
            return true;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }

    /** 每 10 分钟清理已过期的登记。 */
    @Scheduled(fixedDelay = 600_000)
    public void purgeExpired() {
        jdbc.update("delete from aiw_handoff_jti where expires_at < ?", Timestamp.from(Instant.now()));
    }
}
