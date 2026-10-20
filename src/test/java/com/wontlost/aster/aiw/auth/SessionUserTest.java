package com.wontlost.aster.aiw.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class SessionUserTest {
    private static final AiwUser ANN = new AiwUser("user-1", "Ann", null, "hi", "member");

    @AfterEach
    void clear() {
        VaadinSession.setCurrent(null);
    }

    @Test
    void emptyOutsideAnyVaadinSession() {
        assertEquals(Optional.empty(), SessionUser.current());
    }

    @Test
    void readsAttributeFromCurrentVaadinSession() {
        var http = new MockHttpSession();
        SessionUser.set(http, ANN);
        assertEquals(ANN, http.getAttribute("aiw.user"));

        WrappedSession wrapped = mock(WrappedSession.class);
        when(wrapped.getAttribute("aiw.user")).thenReturn(http.getAttribute("aiw.user"));
        VaadinSession vaadin = mock(VaadinSession.class);
        when(vaadin.getSession()).thenReturn(wrapped);
        VaadinSession.setCurrent(vaadin);

        assertEquals(Optional.of(ANN), SessionUser.current());
    }

    @Test
    void emptyWhenVaadinSessionHasNoUser() {
        VaadinSession vaadin = mock(VaadinSession.class);
        when(vaadin.getSession()).thenReturn(mock(WrappedSession.class));
        VaadinSession.setCurrent(vaadin);
        assertTrue(SessionUser.current().isEmpty());
    }
}
