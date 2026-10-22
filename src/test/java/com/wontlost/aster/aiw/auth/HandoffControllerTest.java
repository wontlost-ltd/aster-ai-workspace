package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.web.ColorSchemePreference;
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

    private static final String VAADIN_ATTR = "com.vaadin.flow.server.VaadinSession.springServlet";

    private final HandoffTestKeys keys = new HandoffTestKeys();
    private final HandoffJtiStore jtis = new HandoffJtiStore(H2.fresh());

    private MockMvc mvcAt(long now) {
        return mvcAt(now, jtis);
    }

    private MockMvc mvcAt(long now, HandoffJtiStore store) {
        var verifier = new HandoffTokenVerifier(
                keys.publicKeyB64(), Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(new HandoffController(verifier, store, PROPS)).build();
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

    /** 同一浏览器换了 cloud 账号：旧会话（含 VaadinSession 与其中的会话历史）整体作废，新用户拿到全新会话。 */
    @Test
    void handoffForAnotherUserStartsAFreshSession() throws Exception {
        var old = new MockHttpSession();
        SessionUser.set(old, new AiwUser("someone-else", "Bob", null, "en", "member"));
        old.setAttribute(VAADIN_ATTR, "bob's vaadin session");
        MvcResult r = mvcAt(NOW + 5)
                .perform(get("/handoff").session(old).param("token", token("j-u2")).param("next", "/"))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        var fresh = r.getRequest().getSession(false);
        assertTrue(old.isInvalid(), "旧用户的会话必须失效");
        assertNotSame(old, fresh);
        assertNull(fresh.getAttribute(VAADIN_ATTR));
        assertEquals("user-1", ((AiwUser) fresh.getAttribute(SessionUser.ATTRIBUTE)).userId());
    }

    /** 亮 / 暗偏好随交接参数记进会话；换账号作废旧会话时偏好仍保留（它属于浏览器）。 */
    @Test
    void themeParameterIsRememberedAndSurvivesAnAccountSwitch() throws Exception {
        MvcResult r = mvcAt(NOW + 5)
                .perform(get("/handoff").param("token", token("j-t1")).param("next", "/").param("theme", "dark"))
                .andExpect(redirectedUrl("/"))
                .andExpect(request().sessionAttribute(ColorSchemePreference.ATTRIBUTE, "dark"))
                .andReturn();

        var old = new MockHttpSession();
        SessionUser.set(old, new AiwUser("someone-else", "Bob", null, "en", "member"));
        old.setAttribute(ColorSchemePreference.ATTRIBUTE, "light");
        MvcResult switched = mvcAt(NOW + 5)
                .perform(get("/handoff").session(old).param("token", token("j-t2")).param("next", "/"))
                .andReturn();
        assertTrue(old.isInvalid());
        assertEquals("light", switched.getRequest().getSession(false).getAttribute(ColorSchemePreference.ATTRIBUTE));
        assertNotSame(r.getRequest().getSession(false), switched.getRequest().getSession(false));
    }

    @Test
    void unknownThemeValueIsIgnored() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute(ColorSchemePreference.ATTRIBUTE, "dark");
        mvcAt(NOW + 5)
                .perform(get("/handoff").session(session).param("token", token("j-t3")).param("next", "/")
                        .param("theme", "<script>"))
                .andExpect(request().sessionAttribute(ColorSchemePreference.ATTRIBUTE, "dark"));
    }

    /** 同一用户重新交接：沿用会话（Vaadin 状态保留），只更换会话 id。 */
    @Test
    void handoffForTheSameUserKeepsTheSessionButRotatesItsId() throws Exception {
        var same = new MockHttpSession();
        SessionUser.set(same, new AiwUser("user-1", "Ann", null, "zh", "owner"));
        same.setAttribute(VAADIN_ATTR, "ann's vaadin session");
        String before = same.getId();
        MvcResult r = mvcAt(NOW + 5)
                .perform(get("/handoff").session(same).param("token", token("j-u1")).param("next", "/"))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        var after = r.getRequest().getSession(false);
        assertFalse(same.isInvalid());
        assertEquals("ann's vaadin session", after.getAttribute(VAADIN_ATTR));
        assertNotEquals(before, after.getId());
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

    /** 数据库宕机/抖动时交接不阻断（Review Focus #1、ADR §2.7），但单次使用仍由内存登记保证。 */
    @Test
    void databaseOutageStillCompletesHandoffAndBlocksReplay() throws Exception {
        FlakyJdbc jdbc = H2.flaky();
        var store = new HandoffJtiStore(jdbc, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
        var mvc = mvcAt(NOW + 5, store);
        String t = token("j-down");
        jdbc.down = true;

        mvc.perform(get("/handoff").param("token", t).param("next", "/panel?policyId=p-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/panel?policyId=p-1"))
                .andExpect(request().sessionAttribute(SessionUser.ATTRIBUTE,
                        new AiwUser("user-1", "Ann", "ann@example.com", "zh", "owner")));

        mvc.perform(get("/handoff").param("token", t).param("next", "/panel?policyId=p-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CLOUD_PANEL));

        jdbc.down = false;
        mvc.perform(get("/handoff").param("token", t).param("next", "/panel?policyId=p-1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CLOUD_PANEL));
    }
}
