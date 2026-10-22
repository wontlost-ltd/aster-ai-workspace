package com.wontlost.aster.aiw.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.WrappedSession;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import java.util.HashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 首屏亮 / 暗（F29）：参数 &gt; 会话记录 &gt; 跟随系统，写到 &lt;html&gt; 的 theme 与 color-scheme。 */
class ColorSchemeBootstrapTest {
    private final Map<String, Object> attributes = new HashMap<>();
    private final WrappedSession session = mock(WrappedSession.class);
    private final VaadinRequest request = mock(VaadinRequest.class);

    ColorSchemeBootstrapTest() {
        when(session.getAttribute(ColorSchemePreference.ATTRIBUTE))
                .thenAnswer(i -> attributes.get(ColorSchemePreference.ATTRIBUTE));
        org.mockito.Mockito.doAnswer(i -> attributes.put(i.getArgument(0), i.getArgument(1)))
                .when(session).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    private Element render(String param, boolean hasSession) {
        when(request.getParameter(ColorSchemePreference.PARAM)).thenReturn(param);
        when(request.getWrappedSession(true)).thenReturn(session);
        when(request.getWrappedSession(false)).thenReturn(hasSession ? session : null);
        Document doc = Jsoup.parse("<!doctype html><html><head></head><body></body></html>");
        ColorSchemeBootstrap.apply(new IndexHtmlResponse(request, mock(VaadinResponse.class), doc));
        return doc.head().parent();
    }

    @Test
    void darkParameterIsAppliedAndRemembered() {
        Element html = render("dark", false);
        assertEquals("dark", html.attr("theme"));
        assertEquals("color-scheme: dark;", html.attr("style"));
        assertEquals("dark", attributes.get(ColorSchemePreference.ATTRIBUTE));
    }

    @Test
    void laterLoadsWithoutTheParameterKeepTheRememberedChoice() {
        render("light", false);
        Element html = render(null, true);
        assertEquals("light", html.attr("theme"));
        assertEquals("color-scheme: light;", html.attr("style"));
    }

    @Test
    void parameterOverridesTheRememberedChoice() {
        attributes.put(ColorSchemePreference.ATTRIBUTE, "light");
        assertEquals("dark", render("DARK ", true).attr("theme"));
        assertEquals("dark", attributes.get(ColorSchemePreference.ATTRIBUTE));
    }

    @Test
    void withoutParameterOrRecordItFollowsTheSystemWithoutCreatingASession() {
        Element html = render(null, false);
        assertEquals("light-dark", html.attr("theme"));
        assertEquals("color-scheme: light dark;", html.attr("style"));
        verify(request, never()).getWrappedSession(true);
    }

    @Test
    void invalidParameterIsIgnored() {
        attributes.put(ColorSchemePreference.ATTRIBUTE, "dark");
        assertEquals("dark", render("blue", true).attr("theme"));
        assertEquals("dark", attributes.get(ColorSchemePreference.ATTRIBUTE));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
        "dark, null, DARK",
        "light, dark, LIGHT",
        "null, dark, DARK",
        "auto, light, LIGHT",
        "null, null, SYSTEM",
        "'', bogus, SYSTEM",
    })
    void resolveOrder(String param, String stored, ColorScheme.Value expected) {
        assertEquals(expected, ColorSchemePreference.resolve(param, stored));
    }

    @Test
    void onlyDarkAndLightAreRemembered() {
        assertEquals("dark", ColorSchemePreference.remembered("Dark").orElseThrow());
        assertNull(ColorSchemePreference.remembered("system").orElse(null));
    }
}
