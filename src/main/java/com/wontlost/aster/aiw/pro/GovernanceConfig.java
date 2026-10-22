package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.ee.governance.LegalHoldStore;
import com.wontlost.agui.ee.governance.PatternRedactor;
import com.wontlost.agui.ee.governance.Redactor;
import com.wontlost.agui.ee.governance.RetentionPolicy;
import com.wontlost.agui.ee.governance.RetentionService;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.ConversationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 治理装配（ADR 0049 §2.7）：覆盖 Pro 自动配置的默认脱敏器与保留服务，全部由本服务的授权闸门决定走向。
 *
 * <ul>
 * <li>脱敏器：已授权用 Pro 的 PatternRedactor（邮箱、卡号、IBAN、IPv4 + apikey，电话换成 {@link LocalRedactor#PHONE}），
 *     未授权用等价的 {@link LocalRedactor}；二者都包在 {@link CodeAwareRedactor} 里，只脱敏正文、保留围栏代码块，
 *     避免恢复的建议损坏代码；</li>
 * <li>保留期：RetentionService 懒加载，仅由 {@link RetentionJob} 在授权后取用；
 *     Pro 自带的 cron 注册依赖 cron 属性，这里改由 {@code aiw.retention.cron} 驱动，故 yml 不再设置它。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class GovernanceConfig {
    @Bean
    Redactor redactor(ProFeatures pro) {
        return new CodeAwareRedactor(proseRedactor(pro));
    }

    /** 正文脱敏器；外层 {@link CodeAwareRedactor} 保证围栏代码块不被改写。 */
    private static Redactor proseRedactor(ProFeatures pro) {
        if (!pro.enabled("ee.governance")) {
            return new LocalRedactor();
        }
        // 不用 Pro 的 phone()：它把金额与日期当电话；规则顺序与 LocalRedactor 一致（电话最后）
        return PatternRedactor.builder().email().card().iban().ipv4()
                .pattern("apikey", LocalRedactor.API_KEY, "[key]")
                .pattern("phone", LocalRedactor.PHONE, "[phone]").build();
    }

    @Bean
    @Lazy
    RetentionService retentionService(RetentionPolicy policy, ObjectProvider<ConversationStore> conversations,
            ObjectProvider<AuditTrail> audit, LegalHoldStore holds) {
        return new RetentionService(policy, conversations.getIfAvailable(), audit.getIfAvailable(), holds);
    }

    @Bean
    RetentionJob retentionJob(ProFeatures pro, ObjectProvider<RetentionService> service) {
        return new RetentionJob(pro, service);
    }
}
