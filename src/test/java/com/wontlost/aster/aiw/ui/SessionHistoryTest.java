package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vaadin.flow.server.VaadinSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 会话内存历史放在 VaadinSession 属性 aiw.history（Map<threadId, List<Message>>）。 */
class SessionHistoryTest {
    @Test
    void createsTheAttributeOnFirstUse() {
        VaadinSession session = mock(VaadinSession.class);
        SessionHistory.of(session);
        verify(session).setAttribute(eq(SessionHistory.ATTRIBUTE), any(Map.class));
    }

    @Test
    void reusesAnExistingAttribute() {
        VaadinSession session = mock(VaadinSession.class);
        Map<String, Object> existing = new LinkedHashMap<>();
        when(session.getAttribute(SessionHistory.ATTRIBUTE)).thenReturn(existing);
        SessionHistory.of(session).save("t1", java.util.List.of(com.wontlost.agui.model.Message.user("x")));
        verify(session, never()).setAttribute(eq(SessionHistory.ATTRIBUTE), any());
        assertSame(1, existing.size());
    }
}
