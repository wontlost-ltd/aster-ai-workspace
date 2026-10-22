package com.wontlost.aster.aiw.gdpr;

import com.wontlost.agui.ee.governance.JdbcLegalHoldStore;
import com.wontlost.agui.ee.governance.LegalHoldStore;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.auth.UserSessions;
import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GDPR 端点装配（F23）。密钥取 {@code aiw.gdpr-hmac-key}（AIW_GDPR_HMAC_KEY），经
 * {@link GdprRequestVerifier#effectiveKey} 检查（去空白、至少 32 字节、不得等于 AIW_HMAC_KEY），不合格时端点一律 503。
 *
 * <p>所有 {@link ConversationStore} bean 都包上 {@link ErasureAwareConversationStore}，
 * 清除后打开着的标签页不能把已清除的消息写回。
 */
@Configuration(proxyBeanMethods = false)
public class GdprConfig {
    @Bean
    GdprRequestVerifier gdprRequestVerifier(@Value("${aiw.gdpr-hmac-key:}") String key, AiwProperties props) {
        return new GdprRequestVerifier(GdprRequestVerifier.effectiveKey(key, props.hmacKey()), Clock.systemUTC());
    }

    @Bean
    ErasureLedger erasureLedger(JdbcTemplate jdbc, AiwProperties props) {
        return new ErasureLedger(jdbc, Clock.systemUTC(), Duration.ofDays(Math.max(1, props.retentionDays())));
    }

    @Bean
    static BeanPostProcessor erasureAwareConversationStores(ObjectProvider<ErasureLedger> ledger) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConversationStore store && !(bean instanceof ErasureAwareConversationStore)) {
                    return new ErasureAwareConversationStore(store, ledger.getObject());
                }
                return bean;
            }
        };
    }

    @Bean
    GdprService gdprService(ConversationStore conversations, AuditTrail audit, LegalHoldStore holds, ProFeatures pro,
            DataSource dataSource, JdbcTemplate jdbc, ErasureLedger ledger, UserSessions sessions) {
        return new GdprService(conversations, audit, holds, pro, Clock.systemUTC(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), holdLock(jdbc), ledger, sessions);
    }

    /**
     * 清除事务开头锁住法律冻结表（PostgreSQL SHARE 锁：阻塞并发新增冻结直到提交，读不受影响）；
     * 其它数据库（测试用 H2）不加锁。
     */
    static Runnable holdLock(JdbcTemplate jdbc) {
        String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        if (product == null || !product.toLowerCase(java.util.Locale.ROOT).contains("postgres")) {
            return () -> {};
        }
        return () -> jdbc.execute("LOCK TABLE " + JdbcLegalHoldStore.TABLE + " IN SHARE MODE");
    }

    /** 每天清理过期的清除墓碑。 */
    @Bean
    ErasureLedgerJanitor erasureLedgerJanitor(ErasureLedger ledger) {
        return new ErasureLedgerJanitor(ledger);
    }

    static final class ErasureLedgerJanitor {
        private final ErasureLedger ledger;

        ErasureLedgerJanitor(ErasureLedger ledger) {
            this.ledger = ledger;
        }

        @Scheduled(cron = "0 41 3 * * *")
        void purge() {
            ledger.purgeExpired();
        }
    }
}
