package com.wontlost.aster.aiw.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 交接令牌的单次使用登记（表 aiw_handoff_jti，由 schema-aiw.sql 建表）。
 *
 * <p>以主键冲突实现原子的「先到先得」：多实例并发提交同一 jti 时只有一个 INSERT 成功。
 * 数据库不可用时（ADR §2.7 不阻断）退化为本实例内存登记：交接照常成功，同一 jti 在本实例内仍只能用一次；
 * 内存登记在数据库恢复后继续参与判断，故障期间用过的 jti 恢复后也不能重放。
 * 过期登记可以安全清理——对应令牌本身已因 exp 被拒绝，不可能再被重放成功。
 */
@Component
public class HandoffJtiStore {
    private static final Logger LOG = LoggerFactory.getLogger(HandoffJtiStore.class);
    /** 内存登记上限：60 秒令牌下远超单实例真实交接量，防止故障期间内存无界增长。 */
    static final int MEMORY_CAP = 10_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> memory = new ConcurrentHashMap<>();
    /** 每次故障只告警一次，数据库恢复后复位。 */
    private final AtomicBoolean degraded = new AtomicBoolean();

    @Autowired
    public HandoffJtiStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public HandoffJtiStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 登记 jti；首次返回 true，已被使用（库或内存中）返回 false。 */
    public boolean claim(String jti, Instant expiresAt) {
        if (liveInMemory(jti)) {
            return false;
        }
        try {
            jdbc.update("insert into aiw_handoff_jti (jti, expires_at) values (?, ?)", jti, Timestamp.from(expiresAt));
            degraded.set(false);
            return true;
        } catch (DuplicateKeyException replay) {
            return false;
        } catch (DataAccessException down) {
            if (degraded.compareAndSet(false, true)) {
                LOG.warn("交接 jti 无法写入数据库，退化为本实例内存登记: {}", down.getMessage());
            }
            return claimInMemory(jti, expiresAt);
        }
    }

    private boolean liveInMemory(String jti) {
        Instant exp = memory.get(jti);
        return exp != null && exp.isAfter(clock.instant());
    }

    /** 满额时先清过期项，仍满则按重放拒绝（宁可让用户回 cloud 重签，也不放开单次使用）。 */
    private boolean claimInMemory(String jti, Instant expiresAt) {
        if (memory.size() >= MEMORY_CAP) {
            purgeMemory();
        }
        if (memory.size() >= MEMORY_CAP) {
            return false;
        }
        Instant previous = memory.putIfAbsent(jti, expiresAt);
        return previous == null || (!previous.isAfter(clock.instant()) && memory.replace(jti, previous, expiresAt));
    }

    private void purgeMemory() {
        Instant now = clock.instant();
        memory.values().removeIf(exp -> !exp.isAfter(now));
    }

    /** 每 10 分钟清理已过期的登记（内存与数据库）；数据库不可用时跳过，下次再清。 */
    @Scheduled(fixedDelay = 600_000)
    public void purgeExpired() {
        purgeMemory();
        try {
            jdbc.update("delete from aiw_handoff_jti where expires_at < ?", Timestamp.from(clock.instant()));
        } catch (DataAccessException down) {
            LOG.debug("清理 aiw_handoff_jti 失败，下次重试: {}", down.getMessage());
        }
    }

    int memorySize() {
        return memory.size();
    }
}
