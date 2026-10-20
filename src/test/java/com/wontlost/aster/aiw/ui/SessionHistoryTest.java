package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vaadin.flow.server.VaadinSession;
import com.wontlost.agui.model.Message;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 会话内存历史放在 VaadinSession 属性 aiw.history（Map<userId, Map<threadId, List<Message>>>）。 */
class SessionHistoryTest {
    @Test
    void createsTheAttributeOnFirstUse() {
        VaadinSession session = mock(VaadinSession.class);
        SessionHistory.of(session, "u1");
        verify(session).setAttribute(eq(SessionHistory.ATTRIBUTE), any(Map.class));
    }

    @Test
    void reusesAnExistingAttribute() {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> existing = new LinkedHashMap<>();
        when(session.getAttribute(SessionHistory.ATTRIBUTE)).thenReturn(existing);
        SessionHistory.of(session, "u1").save("t1", List.of(Message.user("x")));
        verify(session, never()).setAttribute(eq(SessionHistory.ATTRIBUTE), any());
        assertSame(1, existing.size());
    }

    /** 同一 VaadinSession 里先后两个用户（交接换号）：历史按用户隔离，同一 threadId 互不可见。 */
    @Test
    void usersSharingOneSessionSeeDisjointHistories() {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> attribute = new LinkedHashMap<>();
        when(session.getAttribute(SessionHistory.ATTRIBUTE)).thenReturn(attribute);
        SessionHistory.of(session, "alice").save("policy:p1", List.of(Message.user("alice secret")));

        SessionHistory bob = SessionHistory.of(session, "bob");
        assertTrue(bob.threadIds().isEmpty());
        assertTrue(bob.find("policy:p1").isEmpty());
        bob.save("policy:p1", List.of(Message.user("bob")));
        assertEquals("alice secret",
                SessionHistory.of(session, "alice").find("policy:p1").orElseThrow().get(0).content());
    }
}
