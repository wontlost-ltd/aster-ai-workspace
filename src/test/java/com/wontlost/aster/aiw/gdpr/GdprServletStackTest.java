package com.wontlost.aster.aiw.gdpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wontlost.aster.aiw.agent.CloudRequestSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 走真实 Servlet 栈（Vaadin 已挂载）：/internal/gdpr/* 由 MVC 控制器处理，无会话、无 CSRF，不被 Vaadin 截走。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GdprServletStackTest {
    @DynamicPropertySource
    static void gdprKey(DynamicPropertyRegistry r) {
        r.add("aiw.gdpr-hmac-key", () -> GdprControllerTest.KEY);
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String sig, String nonce, long ts) throws Exception {
        String body = "{\"userId\":\"stack-user\"}";
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Internal-Caller", "aster-cloud")
                .header("X-Aster-Timestamp", Long.toString(ts))
                .header("X-Aster-Nonce", nonce)
                .header("X-Aster-User-Id", "stack-user")
                .header("X-Internal-Signature", sig == null ? CloudRequestSigner.hmacHex(GdprControllerTest.KEY,
                        CloudRequestSigner.canonical("POST", path, Long.toString(ts), nonce, body, "stack-user")) : sig)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void signedExportAndEraseAreServedAsJson() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        HttpResponse<String> export = post(GdprController.EXPORT, null, "0".repeat(31) + "1", now);
        assertEquals(200, export.statusCode());
        assertTrue(export.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertTrue(export.body().contains("\"userId\":\"stack-user\""));
        assertTrue(export.headers().allValues("Set-Cookie").isEmpty(), "不建会话");

        HttpResponse<String> erase = post(GdprController.ERASE, null, "0".repeat(31) + "2", now);
        assertEquals(200, erase.statusCode());
        assertTrue(erase.body().contains("\"deletedConversations\":0"));
    }

    @Test
    void unsignedCallIsRejectedWithoutReachingVaadin() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        HttpResponse<String> r = post(GdprController.ERASE, "0".repeat(64), "0".repeat(31) + "3", now);
        assertEquals(401, r.statusCode());
        assertEquals("{\"error\":\"bad_signature\"}", r.body());
    }
}
