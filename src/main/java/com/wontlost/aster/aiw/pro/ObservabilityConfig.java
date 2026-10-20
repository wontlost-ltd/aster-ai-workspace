package com.wontlost.aster.aiw.pro;

import com.wontlost.agui.agent.AgUiAgent;
import com.wontlost.agui.ee.observability.ObservedAgent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性按 ee.observability 授权闸门装配：Pro 自动配置无条件包装每个 Agent，而 ObservedAgent 构造即走
 * License.require，故在 application.yml 里把它关掉（agui.ee.observability.enabled=false），由这里按授权接管。
 * 未授权时 Agent 原样运行，{@code aiw.runs} 等自有指标不受影响。
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {
    private static final String FEATURE = ObservedAgent.FEATURE;

    @Bean
    static BeanPostProcessor aiwObservabilityPostProcessor(ObjectProvider<ProFeatures> pro,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                // 先看类型再解析闸门：后置处理器对每个 bean 都会跑，提前解析会在启动早期造成循环依赖
                if (!(bean instanceof AgUiAgent agent) || bean instanceof ObservedAgent) {
                    return bean;
                }
                ProFeatures gate = pro.getIfAvailable();
                if (gate == null || !gate.enabled(FEATURE)) {
                    return bean;
                }
                return new ObservedAgent(agent, beanName, meters.getIfAvailable(), observations.getIfAvailable());
            }
        };
    }
}
