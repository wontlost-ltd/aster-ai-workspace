package com.wontlost.aster.aiw.gdpr;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GDPR 清除的消息墓碑（表 aiw_erased_message）：只记 (用户, 消息 id, 清除时间)，不含内容。
 *
 * <p>清除前已打开的标签页仍在客户端持有完整对话，下一次运行结束时会整段回写；
 * {@link ErasureAwareConversationStore} 写入前据此剔除已清除的消息。
 * 墓碑保留与会话 / 审计同样的天数（aiw.retention-days）后清理：那时任何标签页都早已失效。
 */
public class ErasureLedger {
    private static final Logger LOG = LoggerFactory.getLogger(ErasureLedger.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration keep;

    public ErasureLedger(JdbcTemplate jdbc, Clock clock, Duration keep) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.keep = keep;
    }

    /** 记下被清除的消息 id；已记过的跳过（不用会让 PostgreSQL 事务中止的主键冲突）。 */
    void record(String userId, Collection<String> messageIds, Instant at) {
        Timestamp ts = Timestamp.from(at);
        for (String id : messageIds) {
            jdbc.update("insert into aiw_erased_message (user_id, message_id, erased_at) select ?, ?, ? "
                    + "where not exists (select 1 from aiw_erased_message where user_id = ? and message_id = ?)",
                    userId, id, ts, userId, id);
        }
    }

    /** 该用户所有已清除的消息 id；没有清除过时为空集（主键前缀查询）。 */
    Set<String> erasedMessageIds(String userId) {
        return new HashSet<>(jdbc.queryForList(
                "select message_id from aiw_erased_message where user_id = ?", String.class, userId));
    }

    /** 清理超过保留期的墓碑；数据库不可用时跳过，下次再清。 */
    public void purgeExpired() {
        try {
            jdbc.update("delete from aiw_erased_message where erased_at < ?", Timestamp.from(clock.instant().minus(keep)));
        } catch (DataAccessException down) {
            LOG.debug("清理 aiw_erased_message 失败，下次重试: {}", down.getMessage());
        }
    }
}
