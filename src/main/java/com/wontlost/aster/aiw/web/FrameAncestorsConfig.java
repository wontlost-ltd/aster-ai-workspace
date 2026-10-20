package com.wontlost.aster.aiw.web;

import com.wontlost.aster.aiw.config.AiwProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 以最高优先级注册 frame-ancestors 过滤器，覆盖所有路径。 */
@Configuration(proxyBeanMethods = false)
public class FrameAncestorsConfig {
    @Bean
    FilterRegistrationBean<FrameAncestorsFilter> frameAncestorsFilter(AiwProperties props) {
        var reg = new FilterRegistrationBean<>(new FrameAncestorsFilter(props.cloudOrigin()));
        reg.addUrlPatterns("/*");
        reg.setDispatcherTypes(
                java.util.EnumSet.of(
                        jakarta.servlet.DispatcherType.REQUEST,
                        jakarta.servlet.DispatcherType.ERROR,
                        jakarta.servlet.DispatcherType.ASYNC));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
