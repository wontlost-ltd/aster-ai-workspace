package com.wontlost.aster.aiw.auth;

import com.wontlost.aster.aiw.config.AiwProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 交接验证器与 run 票据（唯一的 TicketVerifier）的装配；不暴露 Clock bean，避免与其它模块冲突。 */
@Configuration(proxyBeanMethods = false)
public class AuthConfig {
    @Bean
    HandoffTokenVerifier handoffTokenVerifier(AiwProperties props) {
        return new HandoffTokenVerifier(props.handoffPublicKey(), Clock.systemUTC());
    }

    @Bean
    UserTicket userTicket(AiwProperties props) {
        return new UserTicket(props.ticketKey(), Clock.systemUTC());
    }
}
