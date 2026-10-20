package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wontlost.aster.aiw.config.AiwProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HandoffControllerTest {
    private static final long NOW = 1_800_000_000L;
    private static final String CLOUD_PANEL =
            "https://aster-lang.cloud/api/ai-workspace/handoff?next=%2Fpanel%3FpolicyId%3Dp-1&locale=zh";
    private static final AiwProperties PROPS = new AiwProperties(
            "https://aster-lang.cloud", "https://aster-lang.cloud", "h", "unused", "t", 180);

    private final HandoffTestKeys keys = new HandoffTestKeys();
    private final HandoffJtiStore jtis = new HandoffJtiStore(H2.fresh());

    private MockMvc mvcAt(long now) {
        var verifier = new HandoffTokenVerifier(
                keys.publicKeyB64(), Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(new HandoffController(verifier, jtis, PROPS)).build();
    }

    private String token(String jti) {
        return keys.sign(HandoffTestKeys.claims(jti, "zh", NOW));
    }

    @Test
    void validTokenEstablishesSessionUserAndRedirectsToNext() throws Exception {
        var session = new MockHttpSession();
        String before = session.getId();
        MvcResult r = mvcAt(NOW + 5)
                .perform(get("/handoff").session(session).param("token", token("j-1")).param("next", "/panel?policyId=p-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/panel?policyId=p-1"))
                .andExpect(request().sessionAttribute(SessionUser.ATTRIBUTE,
                        new AiwUser("user-1", "Ann", "ann@example.com", "zh", "owner")))
                .andReturn();
        assertNotEquals(before, r.getRequest().getSession().getId(), "交接成功后必须更换会话 id（防会话固定）");
    }

    @Test
    void expiredTokenRedirectsToCloudHandoff() throws Exception {
        mvcAt(NOW + 120)
                .perform(get("/handoff").param("token", token("j-1")).param("next", "/panel?policyId=p-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CLOUD_PANEL))
                .andExpect(request().sessionAttribute(SessionUser.ATTRIBUTE, (Object) null));
    }

    @Test
    void replayedTokenRedirectsToCloudHandoff() throws Exception {
        var mvc = mvcAt(NOW + 5);
        String t = token("j-1");
        mvc.perform(get("/handoff").param("token", t).param("next", "/panel?policyId=p-1"))
                .andExpect(redirectedUrl("/panel?policyId=p-1"));
        MvcResult replay = mvc.perform(get("/handoff").param("token", t).param("next", "/panel?policyId=p-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CLOUD_PANEL))
                .andReturn();
        var s = replay.getRequest().getSession(false);
        assertNull(s == null ? null : s.getAttribute(SessionUser.ATTRIBUTE));
    }

    @Test
    void badSignatureRedirectsToCloudHandoffInEnglish() throws Exception {
        String forged = new HandoffTestKeys().sign(HandoffTestKeys.claims("j-1", "zh", NOW));
        mvcAt(NOW).perform(get("/handoff").param("token", forged).param("next", "/panel"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://aster-lang.cloud/api/ai-workspace/handoff?next=%2Fpanel&locale=en"));
    }

    @Test
    void missingTokenRedirectsToCloudHandoff() throws Exception {
        mvcAt(NOW).perform(get("/handoff"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://aster-lang.cloud/api/ai-workspace/handoff?next=%2F&locale=en"));
    }

    @Test
    void disallowedNextFallsBackToRoot() throws Exception {
        var mvc = mvcAt(NOW);
        for (String next : new String[] {"https://evil.example/", "//evil.example", "/panel?policyId=a/b",
                "/admin", "/panel?policyId=" + "x".repeat(65), "/panel?policyId=p-1&x=1"}) {
            mvc.perform(get("/handoff").param("token", token("j-" + next.hashCode())).param("next", next))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/"));
        }
    }

    @Test
    void unsupportedLocaleIsNormalisedToEnglish() throws Exception {
        String t = keys.sign(HandoffTestKeys.claims("j-9", "fr", NOW));
        MvcResult r = mvcAt(NOW).perform(get("/handoff").param("token", t).param("next", "/"))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        var user = (AiwUser) r.getRequest().getSession().getAttribute(SessionUser.ATTRIBUTE);
        assertEquals("en", user.locale());
    }
}
