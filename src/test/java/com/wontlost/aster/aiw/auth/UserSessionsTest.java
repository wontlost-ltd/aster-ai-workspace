package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/** 按用户登记 HTTP 会话，供 GDPR 清除后作废（F23 / I3）。 */
class UserSessionsTest {
    private final UserSessions sessions = new UserSessions();

    private static AiwUser user(String id) {
        return new AiwUser(id, "n", null, "zh", "member");
    }

    private void set(MockHttpSession s, AiwUser value, AiwUser previous) {
        s.setAttribute(SessionUser.ATTRIBUTE, value);
        var event = new HttpSessionBindingEvent(s, SessionUser.ATTRIBUTE, previous == null ? value : previous);
        if (previous == null) {
            sessions.attributeAdded(event);
        } else {
            sessions.attributeReplaced(event);
        }
    }

    @Test
    void invalidatesAllSessionsOfThatUserOnly() {
        var a1 = new MockHttpSession();
        var a2 = new MockHttpSession();
        var b = new MockHttpSession();
        set(a1, user("a"), null);
        set(a2, user("a"), null);
        set(b, user("b"), null);
        assertEquals(2, sessions.invalidate("a"));
        assertTrue(a1.isInvalid() && a2.isInvalid());
        assertFalse(b.isInvalid());
        assertEquals(0, sessions.invalidate("a"), "幂等");
    }

    @Test
    void replacedUserMovesTheSessionAndDestroyedSessionsAreForgotten() {
        var s = new MockHttpSession();
        set(s, user("a"), null);
        set(s, user("b"), user("a"));
        assertEquals(0, sessions.tracked("a"));
        assertEquals(1, sessions.tracked("b"));
        sessions.sessionDestroyed(new HttpSessionEvent(s));
        assertEquals(0, sessions.tracked("b"));
    }

    @Test
    void alreadyInvalidSessionsAreSkipped() {
        var s = new MockHttpSession();
        set(s, user("a"), null);
        s.invalidate();
        assertEquals(0, sessions.invalidate("a"));
    }

    @Test
    void otherAttributesAreIgnored() {
        var s = new MockHttpSession();
        s.setAttribute("x", user("a"));
        sessions.attributeAdded(new HttpSessionBindingEvent(s, "x", user("a")));
        assertEquals(0, sessions.tracked("a"));
    }
}
