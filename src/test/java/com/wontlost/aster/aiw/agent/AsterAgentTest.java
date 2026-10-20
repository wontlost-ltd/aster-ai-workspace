package com.wontlost.aster.aiw.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wontlost.agui.agent.AgUiEmitter;
import com.wontlost.agui.event.AgUiEvent;
import com.wontlost.agui.model.ContextItem;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.model.RunAgentInput;
import com.wontlost.aster.aiw.auth.TicketUser;
import com.wontlost.aster.aiw.auth.TicketVerifier;
import com.wontlost.aster.aiw.i18n.ErrorTexts;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** 以进程内 HttpServer 冒充 cloud，验证 AsterAgent 的签名、请求体、帧翻译、错误与取消。 */
class AsterAgentTest {

    private static final String KEY = "k".repeat(64);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String HAPPY_SSE = """
            data:{"type":"meta","data":"test-model"}

            data:{"type":"upstream_reached"}

            data:{"type":"delta","data":"Hi"}

            data:{"type":"final","data":"Hi","validated":false}

            """;

    /** 每个测试设置的 cloud 行为。 */
    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange ex) throws IOException;
    }

    record Seen(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> v = headers.get(name);
            return v == null ? null : v.get(0);
        }
    }

    private HttpServer server;
    private volatile Responder responder;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private SimpleMeterRegistry meters;
    private AsterAgent agent;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body));
            try {
                responder.respond(ex);
            } finally {
                ex.close();
            }
        });
        server.start();
        meters = new SimpleMeterRegistry();
        var verifier = (TicketVerifier) ticket -> switch (ticket) {
            case "good-en" -> Optional.of(new TicketUser("u1", "en"));
            case "good-zh" -> Optional.of(new TicketUser("u1", "zh"));
            default -> Optional.empty();
        };
        var beans = new StaticListableBeanFactory(Map.of("tickets", verifier));
        var signer = new CloudRequestSigner(KEY, Clock.systemUTC(), CloudRequestSigner.randomNonce());
        var client = new CloudLlmClient(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", signer);
        agent = new AsterAgent(beans.getBeanProvider(TicketVerifier.class), client, meters);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ---- 工具 -----------------------------------------------------------

    private static RunAgentInput input(String ticket, String mode, List<ContextItem> ctx, Message... messages) {
        var props = MAPPER.createObjectNode();
        if (ticket != null) {
            props.put("ticket", ticket);
        }
        if (mode != null) {
            props.put("mode", mode);
        }
        return new RunAgentInput("th1", "run1", null, List.of(messages), List.of(), ctx, props);
    }

    private static Responder sse(String text) {
        return ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static Responder json(int status, String body) {
        return ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        };
    }

    private RecordingEmitter run(RunAgentInput in) {
        var emitter = new RecordingEmitter();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> agent.run(in, emitter));
        assertTrue(emitter.completed, "必须以 complete() 收尾");
        return emitter;
    }

    private double runs(String mode, String outcome, String code) {
        var c = meters.find("aiw.runs").tags("mode", mode, "outcome", outcome, "code", code).counter();
        return c == null ? 0 : c.count();
    }

    private io.micrometer.core.instrument.Timer duration(String mode, String outcome) {
        return meters.find("aiw.run.duration").tags("mode", mode, "outcome", outcome).timer();
    }

    @Test
    void happyRunRecordsDurationWithoutCodeTag() throws Exception {
        responder = sse(HAPPY_SSE);
        run(input("good-en", "ask", List.of(), Message.user("q")));
        var t = duration("ask", "ok");
        assertEquals(1, t.count());
        assertTrue(t.getId().getTag("code") == null, "耗时指标不得带 code 标签");
    }

    @Test
    void errorRunRecordsDuration() {
        run(input("bad", "ask", List.of(), Message.user("q")));
        assertEquals(1, duration("ask", "error").count());
    }

    private static String hmac(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- 用例 -----------------------------------------------------------

    @Test
    void happyPathSignsRequestAndTranslatesFrames() throws Exception {
        responder = sse(HAPPY_SSE);
        var events = run(input("good-en", "ask", List.of(), Message.user("hello"))).events;

        assertInstanceOf(AgUiEvent.RunStarted.class, events.get(0));
        var custom = events.stream().filter(AgUiEvent.Custom.class::isInstance).map(AgUiEvent.Custom.class::cast)
                .findFirst().orElseThrow();
        assertEquals("aster.model", custom.name());
        assertTrue(events.stream().anyMatch(AgUiEvent.TextMessageStart.class::isInstance));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgUiEvent.TextMessageContent c && c.delta().equals("Hi")));
        assertTrue(events.stream().anyMatch(AgUiEvent.TextMessageEnd.class::isInstance));
        var snapshot = events.stream().filter(AgUiEvent.MessagesSnapshot.class::isInstance)
                .map(AgUiEvent.MessagesSnapshot.class::cast).findFirst().orElseThrow();
        assertEquals(2, snapshot.messages().size(), "快照必须包含完整对话历史");
        var finished = assertInstanceOf(AgUiEvent.RunFinished.class, events.get(events.size() - 1));
        assertEquals("th1", finished.threadId());
        assertEquals("run1", finished.runId());
        assertFalse(events.stream().anyMatch(AgUiEvent.RunError.class::isInstance));

        assertEquals(1, seen.size());
        Seen s = seen.get(0);
        assertEquals("POST", s.method());
        assertEquals("/api/llm/assistant", s.path());
        assertEquals("{\"query\":\"hello\",\"groundingHits\":[],\"locale\":\"en-US\"}", s.body());
        assertEquals("ai-workspace", s.header("X-internal-caller"));
        assertEquals("u1", s.header("X-aster-user-id"));
        assertEquals("text/event-stream", s.header("Accept"));
        assertTrue(s.header("Content-type").startsWith("application/json"));
        String nonce = s.header("X-aster-nonce");
        assertTrue(nonce.matches("^[0-9a-f]{32}$"));
        String ts = s.header("X-aster-timestamp");
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(ts)) < 60);
        String canonical = String.join("\n", "POST", "/api/llm/assistant", ts, nonce, sha256(s.body()), "u1");
        assertEquals(hmac(canonical), s.header("X-internal-signature"));

        assertEquals(1.0, runs("ask", "ok", "none"));
    }

    @Test
    void denialBecomesLocalizedRunError() {
        responder = json(429, "{\"error\":\"ai_quota_exhausted\",\"message\":\"Quota exceeded\"}");
        var events = run(input("good-zh", "generate", List.of(), Message.user("g"))).events;

        List<AgUiEvent.RunError> errors = events.stream().filter(AgUiEvent.RunError.class::isInstance)
                .map(AgUiEvent.RunError.class::cast).toList();
        assertEquals(1, errors.size());
        assertEquals("ai_quota_exhausted", errors.get(0).code());
        assertEquals(ErrorTexts.get("zh", "ai_quota_exhausted"), errors.get(0).message());
        assertFalse(events.stream().anyMatch(AgUiEvent.RunFinished.class::isInstance));
        assertFalse(events.stream().anyMatch(AgUiEvent.TextMessageContent.class::isInstance));
        assertEquals(1.0, runs("generate", "error", "ai_quota_exhausted"));
    }

    @Test
    void unknownDenialFallsBackToCloudMessage() {
        responder = json(503, "{\"error\":{\"code\":\"ASSISTANT_DISABLED\",\"message\":\"站内助手已被管理员关闭\"}}");
        var events = run(input("good-en", "ask", List.of(), Message.user("q"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("assistant_disabled", err.code());
        assertEquals("站内助手已被管理员关闭", err.message());
    }

    @Test
    void nonJsonDenialUsesHttpStatusCode() {
        responder = ex -> {
            ex.sendResponseHeaders(502, 9);
            ex.getResponseBody().write("<html/>\n\n".getBytes(StandardCharsets.UTF_8), 0, 9);
        };
        var events = run(input("good-en", "ask", List.of(), Message.user("q"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("http_502", err.code());
        assertEquals(ErrorTexts.get("en", "unknown"), err.message());
    }

    @Test
    void unauthorizedLiteralIsNormalised() {
        responder = json(401, "{\"error\":\"Unauthorized\"}");
        var events = run(input("good-en", "ask", List.of(), Message.user("q"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("unauthorized", err.code());
        assertEquals(ErrorTexts.get("en", "unauthorized"), err.message());
    }

    @Test
    void oversizedSourceIsRejectedBeforeCallingCloud() {
        responder = sse(HAPPY_SSE);
        var ctx = List.of(new ContextItem("policy.source", "x".repeat(70_000)));
        var events = run(input("good-en", "suggest", ctx, Message.user("f"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("source_too_large", err.code());
        assertEquals(0, seen.size());
        assertEquals(1.0, runs("suggest", "error", "source_too_large"));
    }

    @Test
    void invalidOrMissingTicketIsSessionExpired() {
        responder = sse(HAPPY_SSE);
        for (String ticket : new String[] {"forged", null}) {
            var events = run(input(ticket, "ask", List.of(), Message.user("q"))).events;
            var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                    .findFirst().orElseThrow();
            assertEquals("session_expired", err.code());
            assertEquals(ErrorTexts.get("en", "session_expired"), err.message());
        }
        assertEquals(0, seen.size());
    }

    @Test
    void absentVerifierBeanMeansSessionExpired() {
        var none = new StaticListableBeanFactory().getBeanProvider(TicketVerifier.class);
        var signer = new CloudRequestSigner(KEY, Clock.systemUTC(), CloudRequestSigner.randomNonce());
        agent = new AsterAgent(none, new CloudLlmClient(HttpClient.newHttpClient(), "http://127.0.0.1:1", signer), meters);
        var events = run(input("good-en", "ask", List.of(), Message.user("q"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("session_expired", err.code());
    }

    /** 未配置 HMAC 密钥：签名抛 IllegalStateException，必须变成本地化的 cloud_misconfigured 而非内部原文。 */
    @Test
    void blankHmacKeyIsLocalizedMisconfiguration() {
        responder = sse(HAPPY_SSE);
        var verifier = (TicketVerifier) t -> Optional.of(new TicketUser("u1", "zh"));
        var beans = new StaticListableBeanFactory(Map.of("tickets", verifier));
        var client = new CloudLlmClient(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new CloudRequestSigner("", Clock.systemUTC(), CloudRequestSigner.randomNonce()));
        agent = new AsterAgent(beans.getBeanProvider(TicketVerifier.class), client, meters);

        var events = run(input("any", "ask", List.of(), Message.user("q"))).events;
        assertInstanceOf(AgUiEvent.RunStarted.class, events.get(0));
        List<AgUiEvent.RunError> errors = events.stream().filter(AgUiEvent.RunError.class::isInstance)
                .map(AgUiEvent.RunError.class::cast).toList();
        assertEquals(1, errors.size());
        assertEquals("cloud_misconfigured", errors.get(0).code());
        assertEquals(ErrorTexts.get("zh", "cloud_misconfigured"), errors.get(0).message());
        assertFalse(events.stream().anyMatch(AgUiEvent.RunFinished.class::isInstance));
        assertEquals(0, seen.size());
        assertEquals(1.0, runs("ask", "error", "cloud_misconfigured"));
    }

    /** 校验器自身故障是服务端配置问题，不是会话过期（否则刷新也无济于事，用户会陷入重试循环）。 */
    @Test
    void throwingVerifierIsMisconfigurationNotSessionExpiry() {
        responder = sse(HAPPY_SSE);
        var verifier = (TicketVerifier) t -> {
            throw new IllegalStateException("aiw.ticket-key 无效");
        };
        var beans = new StaticListableBeanFactory(Map.of("tickets", verifier));
        var signer = new CloudRequestSigner(KEY, Clock.systemUTC(), CloudRequestSigner.randomNonce());
        agent = new AsterAgent(beans.getBeanProvider(TicketVerifier.class),
                new CloudLlmClient(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort(), signer),
                meters);

        var events = run(input("any", "generate", List.of(), Message.user("q"))).events;
        assertInstanceOf(AgUiEvent.RunStarted.class, events.get(0));
        List<AgUiEvent.RunError> errors = events.stream().filter(AgUiEvent.RunError.class::isInstance)
                .map(AgUiEvent.RunError.class::cast).toList();
        assertEquals(1, errors.size());
        assertEquals("cloud_misconfigured", errors.get(0).code());
        assertEquals(ErrorTexts.get("en", "cloud_misconfigured"), errors.get(0).message());
        assertFalse(events.stream().anyMatch(AgUiEvent.RunFinished.class::isInstance));
        assertEquals(0, seen.size());
        assertEquals(1.0, runs("generate", "error", "cloud_misconfigured"));
    }

    /** 非法 aiw.cloud-url（URI.create 抛异常）同样归为 cloud_misconfigured。 */
    @Test
    void invalidCloudUrlIsMisconfiguration() {
        var verifier = (TicketVerifier) t -> Optional.of(new TicketUser("u1", "de"));
        var beans = new StaticListableBeanFactory(Map.of("tickets", verifier));
        var signer = new CloudRequestSigner(KEY, Clock.systemUTC(), CloudRequestSigner.randomNonce());
        agent = new AsterAgent(beans.getBeanProvider(TicketVerifier.class),
                new CloudLlmClient(HttpClient.newHttpClient(), "not a url", signer), meters);
        var events = run(input("any", "ask", List.of(), Message.user("q"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("cloud_misconfigured", err.code());
        assertEquals(ErrorTexts.get("de", "cloud_misconfigured"), err.message());
        assertEquals(1.0, runs("ask", "error", "cloud_misconfigured"));
    }

    @Test
    void providerErrorFrameConcatenatesLocalizedTextAndProviderMessage() {
        responder = sse("""
                data:{"type":"meta","data":"m"}

                data:{"type":"upstream_reached"}

                data:{"type":"delta","data":"par"}

                data:{"type":"error","error":"provider_bad_request","message":"`temperature` is deprecated"}

                data:{"type":"delta","data":"after-error"}

                """);
        var events = run(input("good-en", "suggest", List.of(new ContextItem("policy.source", "s")))).events;
        List<AgUiEvent.RunError> errors = events.stream().filter(AgUiEvent.RunError.class::isInstance)
                .map(AgUiEvent.RunError.class::cast).toList();
        assertEquals(1, errors.size());
        assertEquals("provider_bad_request", errors.get(0).code());
        assertEquals(ErrorTexts.get("en", "provider_bad_request") + "\n`temperature` is deprecated",
                errors.get(0).message());
        assertFalse(events.stream().anyMatch(e -> e instanceof AgUiEvent.TextMessageContent c
                && c.delta().equals("after-error")), "首个错误后必须停止读流");
        assertFalse(events.stream().anyMatch(AgUiEvent.RunFinished.class::isInstance));
        assertEquals(1.0, runs("suggest", "error", "provider_bad_request"));
    }

    @Test
    void legacyErrorFrameWithoutMessageUsesRawText() {
        responder = sse("data:{\"type\":\"error\",\"error\":\"未配置 LLM API Key\"}\n\n");
        var events = run(input("good-en", "ask", List.of(), Message.user("q"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("未配置 LLM API Key", err.message());
    }

    @Test
    void unreachableCloudIsNetworkError() throws IOException {
        server.stop(0);
        var events = run(input("good-en", "ask", List.of(), Message.user("q"))).events;
        var err = events.stream().filter(AgUiEvent.RunError.class::isInstance).map(AgUiEvent.RunError.class::cast)
                .findFirst().orElseThrow();
        assertEquals("network", err.code());
        assertEquals(ErrorTexts.get("en", "network"), err.message());
        assertEquals(1.0, runs("ask", "error", "network"));
    }

    @Test
    void cancellationClosesCloudStream() throws InterruptedException {
        var clientGone = new CountDownLatch(1);
        responder = ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write("data:{\"type\":\"delta\",\"data\":\"Hi\"}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            keepAliveUntilClientCloses(out, clientGone);
        };
        var emitter = new RecordingEmitter();
        emitter.cancelAfterFirstDelta = true;
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertDoesNotThrow(() -> agent.run(
                input("good-en", "ask", List.of(), Message.user("q")), emitter)));
        assertTrue(clientGone.await(5, TimeUnit.SECONDS), "cloud 连接必须被关闭");
        assertFalse(emitter.events.stream().anyMatch(AgUiEvent.RunFinished.class::isInstance));
        assertFalse(emitter.events.stream().anyMatch(AgUiEvent.RunError.class::isInstance));
    }

    /** 持续写注释帧，直到写失败（客户端已断开）或超时。 */
    private static void keepAliveUntilClientCloses(OutputStream out, CountDownLatch clientGone) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try {
            while (System.nanoTime() < deadline) {
                out.write(":ka\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(20);
            }
        } catch (IOException e) {
            clientGone.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 记录事件的 emitter；可在首个文本增量后模拟客户端断开。 */
    static final class RecordingEmitter implements AgUiEmitter {
        final List<AgUiEvent> events = new CopyOnWriteArrayList<>();
        final List<Runnable> cancelCallbacks = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;
        volatile boolean completed;
        boolean cancelAfterFirstDelta;

        @Override
        public void emit(AgUiEvent event) {
            if (cancelled) {
                return;
            }
            events.add(event);
            if (cancelAfterFirstDelta && event instanceof AgUiEvent.TextMessageContent) {
                cancel();
            }
        }

        private void cancel() {
            cancelled = true;
            // 与真实 SSE 断开一样，在另一个线程上触发取消回调
            Thread.ofVirtual().start(() -> cancelCallbacks.forEach(Runnable::run));
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void error(Throwable cause) {
            throw new AssertionError("agent 不应走 emitter.error", cause);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onCancel(Runnable callback) {
            if (cancelled) {
                callback.run();
                return;
            }
            cancelCallbacks.add(callback);
        }
    }
}
