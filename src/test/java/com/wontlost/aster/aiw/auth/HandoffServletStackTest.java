package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 走真实 Servlet 栈（Vaadin 已挂载）：/handoff 必须由 MVC 控制器处理，不能被 Vaadin 截走。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HandoffServletStackTest {
    private static final HandoffTestKeys KEYS = new HandoffTestKeys();

    @DynamicPropertySource
    static void handoffKey(DynamicPropertyRegistry r) {
        r.add("aiw.handoff-public-key", KEYS::publicKeyB64);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> handoff(String token, String next) throws Exception {
        String q = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/handoff?" + q)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void handoffIsServedByMvcAndConsumesJti() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String token = KEYS.sign(HandoffTestKeys.claims("stack-jti-1", "de", now));

        HttpResponse<String> first = handoff(token, "/panel?policyId=p-1");
        assertEquals(302, first.statusCode());
        assertEquals("/panel?policyId=p-1", URI.create(first.headers().firstValue("Location").orElseThrow()).getPath()
                + "?" + URI.create(first.headers().firstValue("Location").orElseThrow()).getQuery());
        assertTrue(first.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("JSESSIONID=")));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from aiw_handoff_jti where jti = ?", Integer.class, "stack-jti-1"));

        HttpResponse<String> replay = handoff(token, "/panel?policyId=p-1");
        assertEquals(302, replay.statusCode());
        assertEquals("https://cloud.test/api/ai-workspace/handoff?next=%2Fpanel%3FpolicyId%3Dp-1&locale=de",
                replay.headers().firstValue("Location").orElseThrow());
        assertEquals("frame-ancestors https://cloud.test",
                replay.headers().firstValue("Content-Security-Policy").orElseThrow());
    }

    /** AsterAgent 经 ObjectProvider 取唯一的 TicketVerifier，必须是 UserTicket。 */
    @Test
    void exactlyOneTicketVerifierBeanWhichIsUserTicket() {
        var beans = context.getBeansOfType(TicketVerifier.class);
        assertEquals(1, beans.size());
        assertInstanceOf(UserTicket.class, beans.values().iterator().next());
    }
}
