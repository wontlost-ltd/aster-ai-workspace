package com.wontlost.aster.aiw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** 整体上下文（含 Pro 自动配置）能在 test profile 下启动，且就绪探针为 UP。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ApplicationContextTest {
    @Autowired TestRestTemplate http;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("aster")
    com.wontlost.agui.agent.AgUiAgent agent;

    @Test
    void healthIsUp() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health/readiness", String.class);
        assertEquals(200, r.getStatusCode().value());
    }

    /** 真实响应链（含 Vaadin/Actuator）下，最终响应带 frame-ancestors 且无 X-Frame-Options。 */
    @Test
    void realResponsesAllowFramingByCloudOnly() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health/liveness", String.class);
        assertEquals(
                java.util.List.of("frame-ancestors https://cloud.test"),
                r.getHeaders().get("Content-Security-Policy"));
        assertNull(r.getHeaders().get("X-Frame-Options"));
    }

    /** 404 走错误分派，也必须带 frame-ancestors 且无 X-Frame-Options。 */
    @Test
    void notFoundResponsesAlsoAllowFramingByCloudOnly() {
        ResponseEntity<String> r = http.getForEntity("/no-such-path-xyz", String.class);
        assertEquals(java.util.List.of("frame-ancestors https://cloud.test"),
                r.getHeaders().get("Content-Security-Policy"));
        assertNull(r.getHeaders().get("X-Frame-Options"));
    }

    /** Vaadin 根路由（工作台视图，未登录时由视图跳转交接）同样满足。 */
    @Test
    void vaadinRootResponseAllowsFramingByCloudOnly() {
        ResponseEntity<String> r = http.getForEntity("/", String.class);
        assertEquals(java.util.List.of("frame-ancestors https://cloud.test"),
                r.getHeaders().get("Content-Security-Policy"));
        assertNull(r.getHeaders().get("X-Frame-Options"));
    }

    /** /actuator/prometheus 可达，且 AsterAgent 的 aiw.runs 计数随运行出现（未授权时不依赖 Pro 可观测）。 */
    @Test
    void prometheusExposesAiwRuns() {
        var input = new com.wontlost.agui.model.RunAgentInput("t", "r", null, java.util.List.of(),
                java.util.List.of(), java.util.List.of(),
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode());
        agent.run(input, org.mockito.Mockito.mock(com.wontlost.agui.agent.AgUiEmitter.class));

        ResponseEntity<String> r = http.getForEntity("/actuator/prometheus", String.class);
        assertEquals(200, r.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertTrue(r.getBody().contains("aiw_runs_total"), r.getBody());
    }
}
