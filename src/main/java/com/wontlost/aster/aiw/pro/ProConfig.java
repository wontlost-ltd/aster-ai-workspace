package com.wontlost.aster.aiw.pro;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pro 闸门装配；ConversationStore / AuditTrail 仍由 Pro 自动配置提供，这里不覆盖。 */
@Configuration(proxyBeanMethods = false)
public class ProConfig {
    @Bean
    ProFeatures proFeatures() {
        return ProFeatures.fromLicense();
    }
}
