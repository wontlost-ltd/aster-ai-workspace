package com.wontlost.aster.aiw.pro;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.wontlost.agui.agent.AgUiAgent;
import com.wontlost.agui.agent.AgUiEmitter;
import com.wontlost.agui.ee.observability.ObservedAgent;
import com.wontlost.agui.model.RunAgentInput;
import com.wontlost.agui.pro.license.License;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** 可观测性按 ee.observability 授权闸门装配：授权则包装并暴露 agui.*，未授权不触达 License.require。 */
class ObservabilityConfigTest {
    @TempDir Path home;

    @BeforeEach
    void expiredTrial() throws Exception {
        System.setProperty(License.HOME_PROPERTY, home.toString());
        License.configure(null);
        Files.createDirectories(home.resolve(".agui-pro"));
        Files.writeString(home.resolve(".agui-pro/trial"), "2000-01-01");
    }

    @AfterEach
    void restore() {
        System.clearProperty(License.HOME_PROPERTY);
        License.configure(null);
    }

    private ApplicationContextRunner runner(boolean licensed, PrometheusMeterRegistry meters) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.wontlost.agui.ee.observability.ObservabilityAutoConfiguration.class))
                .withUserConfiguration(ObservabilityConfig.class)
                .withPropertyValues("agui.ee.observability.enabled=false")
                .withBean(ProFeatures.class, () -> new ProFeatures(f -> licensed && f.equals("ee.observability")))
                .withBean(MeterRegistry.class, () -> meters)
                .withBean("aster", AgUiAgent.class, () -> (in, out) -> out.complete());
    }

    @Test
    void licensedAgentIsObservedAndMetricsAreScrapable() throws Exception {
        Files.writeString(home.resolve(".agui-pro/trial"), java.time.LocalDate.now().toString());
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        runner(true, meters).run(ctx -> {
            AgUiAgent agent = ctx.getBean("aster", AgUiAgent.class);
            assertInstanceOf(ObservedAgent.class, agent);
            agent.run(new RunAgentInput("t", "r", null, List.of(), List.of(), List.of(),
                    JsonMapper.builder().build().createObjectNode()), mock(AgUiEmitter.class));
            String scrape = meters.scrape();
            assertTrue(scrape.contains("agui_run"), scrape);
            assertTrue(scrape.contains("agui_runs_active"), scrape);
        });
    }

    @Test
    void unlicensedAgentIsLeftUnwrappedAndNeverTouchesTheLicenseGate() {
        runner(false, new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)).run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
            assertFalse(ctx.getBean("aster", AgUiAgent.class) instanceof ObservedAgent);
        });
    }
}
