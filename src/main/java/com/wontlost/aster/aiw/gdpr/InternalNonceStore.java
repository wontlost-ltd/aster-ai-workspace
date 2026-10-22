package com.wontlost.aster.aiw.gdpr;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * cloud → 本服务内部调用的 nonce 单次使用登记（表 aiw_internal_nonce，由 schema-aiw.sql 建表）。
 *
 * <p>以主键冲突实现原子的「先到先得」，多实例共享。与交接 jti 不同，这里数据库不可用时不退化为内存登记：
 * 导出 / 清除可以晚点重试，宁可拒绝（调用方得到 503）也不放开单次使用。
 */
@Component
public class InternalNonceStore {
    private static final Logger LOG = LoggerFactory.getLogger(InternalNonceStore.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public InternalNonceStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    InternalNonceStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 登记 nonce；首次返回 true，已用过返回 false。
     *
     * @throws DataAccessException 数据库不可用（调用方按 503 处理）
     */
    public boolean claim(String nonce, Instant expiresAt) {
        try {
            jdbc.update("insert into aiw_internal_nonce (nonce, expires_at) values (?, ?)", nonce, Timestamp.from(expiresAt));
            return true;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }

    /** 每 10 分钟清理已过期的登记；过期 nonce 对应的时间戳已在窗口外，清掉后也无法重放。 */
    @Scheduled(fixedDelay = 600_000)
    public void purgeExpired() {
        try {
            jdbc.update("delete from aiw_internal_nonce where expires_at < ?", Timestamp.from(clock.instant()));
        } catch (DataAccessException down) {
            LOG.debug("清理 aiw_internal_nonce 失败，下次重试: {}", down.getMessage());
        }
    }
}
