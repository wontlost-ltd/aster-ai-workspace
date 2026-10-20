package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wontlost.aster.aiw.config.AiwProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 交接被拒时每个原因记一行 WARN（便于排查密钥不匹配导致的重定向循环），且不含令牌内容。 */
class HandoffRejectionLogTest {
    private static final long NOW = 1_800_000_000L;
    private static final AiwProperties PROPS = new AiwProperties(
            "https://aster-lang.cloud", "https://aster-lang.cloud", "h", "unused", "t", 180);

    private final HandoffTestKeys keys = new HandoffTestKeys();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> loggers = List.of(
            (Logger) LoggerFactory.getLogger(HandoffController.class),
            (Logger) LoggerFactory.getLogger(HandoffTokenVerifier.class));

    @BeforeEach
    void attach() {
        logs.start();
        loggers.forEach(l -> l.addAppender(logs));
    }

    @AfterEach
    void detach() {
        loggers.forEach(l -> l.detachAppender(logs));
    }

    private MockMvc mvcAt(long now) {
        var verifier = new HandoffTokenVerifier(
                keys.publicKeyB64(), Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(
                new HandoffController(verifier, new HandoffJtiStore(H2.fresh()), PROPS)).build();
    }

    /** 恰好一行 WARN，含原因码且不含令牌任何片段。 */
    private void assertSingleWarn(String reason, String token) {
        var warn = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warn.size(), () -> "应恰好一行 WARN: " + logs.list);
        String line = warn.get(0).getFormattedMessage();
        assertTrue(line.contains(reason), line);
        for (String part : token.split("\\.")) {
            assertFalse(!part.isEmpty() && line.contains(part), "日志不得包含令牌内容: " + line);
        }
        assertFalse(line.contains("user-1") || line.contains("ann@example.com"), line);
    }

    private void hit(MockMvc mvc, String token) throws Exception {
        mvc.perform(get("/handoff").param("token", token).param("next", "/"));
    }

    @Test
    void badSignatureIsLogged() throws Exception {
        String forged = new HandoffTestKeys().sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        hit(mvcAt(NOW), forged);
        assertSingleWarn("bad_signature", forged);
    }

    @Test
    void malformedTokenIsLogged() throws Exception {
        hit(mvcAt(NOW), "not-a-token");
        assertSingleWarn("bad_format", "not-a-token");
    }

    @Test
    void wrongAudienceIsLogged() throws Exception {
        Map<String, Object> claims = HandoffTestKeys.claims("j-1", "zh", NOW);
        claims.put("aud", "elsewhere");
        String token = keys.sign(claims);
        hit(mvcAt(NOW), token);
        assertSingleWarn("bad_audience", token);
    }

    @Test
    void expiredTokenIsLogged() throws Exception {
        String token = keys.sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        hit(mvcAt(NOW + 120), token);
        assertSingleWarn("expired", token);
    }

    @Test
    void replayedTokenIsLogged() throws Exception {
        String token = keys.sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        MockMvc mvc = mvcAt(NOW + 5);
        hit(mvc, token);
        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.WARN), "成功交接不记 WARN");
        hit(mvc, token);
        assertSingleWarn("replayed", token);
    }
}
