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
}
