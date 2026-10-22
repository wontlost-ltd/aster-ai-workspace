package com.wontlost.aster.aiw.gdpr;

import com.wontlost.agui.ee.governance.LegalHoldStore;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** GDPR 端点装配；密钥取 {@code aiw.gdpr-hmac-key}（AIW_GDPR_HMAC_KEY），未设置时端点一律 503。 */
@Configuration(proxyBeanMethods = false)
public class GdprConfig {
    @Bean
    GdprRequestVerifier gdprRequestVerifier(@Value("${aiw.gdpr-hmac-key:}") String key) {
        return new GdprRequestVerifier(key, Clock.systemUTC());
    }

    @Bean
    GdprService gdprService(ConversationStore conversations, AuditTrail audit, LegalHoldStore holds, ProFeatures pro) {
        return new GdprService(conversations, audit, holds, pro, Clock.systemUTC());
    }
}
