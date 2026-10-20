package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.Page;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import com.wontlost.agui.pro.audit.AuditEvent;
import com.wontlost.agui.pro.audit.AuditExplorer;
import com.wontlost.agui.pro.audit.InMemoryAuditTrail;
import com.wontlost.agui.pro.license.License;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.SessionUser;
import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 审计浏览器访问控制：仅 owner/admin 且 audit 已授权；按用户租户取数。 */
class AuditViewAccessTest {
    private static final AiwProperties PROPS =
            new AiwProperties("https://cloud.test", "https://cloud.test", "h", "k", "t", 180);

    @TempDir Path proHome;
    private final UI ui = spy(new UI());
    private final Page page = mock(Page.class);
    private final WrappedSession wrapped = mock(WrappedSession.class);

    @BeforeEach
    void setUp() {
        System.setProperty(License.HOME_PROPERTY, proHome.toString());
        License.configure(null);
        VaadinSession session = mock(VaadinSession.class);
        when(session.getSession()).thenReturn(wrapped);
        VaadinSession.setCurrent(session);
        doReturn(page).when(ui).getPage();
        UI.setCurrent(ui);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
        System.clearProperty(License.HOME_PROPERTY);
        License.configure(null);
    }

    private BeforeEnterEvent enter() {
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        when(event.getLocation()).thenReturn(new Location("audit", QueryParameters.of("kind", "RUN_ERROR")));
        when(event.getUI()).thenReturn(ui);
        return event;
    }

    private AuditView view(boolean audit, InMemoryAuditTrail trail) {
        return new AuditView(new AiwI18nProvider(), new ProFeatures(f -> audit && f.equals("audit")), trail, PROPS);
    }

    private static Stream<Component> walk(Component c) {
        return Stream.concat(Stream.of(c), c.getChildren().flatMap(AuditViewAccessTest::walk));
    }

    private void as(String role) {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(new AiwUser("u1", "Ann", null, "en", role));
    }

    @Test
    void memberIsRedirectedHome() {
        as("member");
        BeforeEnterEvent event = enter();
        AuditView v = view(true, new InMemoryAuditTrail());
        v.beforeEnter(event);
        verify(event).forwardTo("");
        assertEquals(0, v.getComponentCount());
    }

    @Test
    void viewerIsRedirectedHome() {
        as("viewer");
        BeforeEnterEvent event = enter();
        view(true, new InMemoryAuditTrail()).beforeEnter(event);
        verify(event).forwardTo("");
    }

    @Test
    void adminWithoutAuditLicenseIsRedirectedHome() {
        as("admin");
        BeforeEnterEvent event = enter();
        AuditView v = view(false, new InMemoryAuditTrail());
        v.beforeEnter(event);
        verify(event).forwardTo("");
        assertEquals(0, v.getComponentCount());
    }

    @Test
    void withoutSessionUserRedirectsToCloudHandoffAndNextFallsBackToRoot() {
        ui.setLocale(java.util.Locale.ENGLISH);
        BeforeEnterEvent event = enter();
        view(true, new InMemoryAuditTrail()).beforeEnter(event);
        verify(event).forwardToUrl("https://cloud.test/api/ai-workspace/handoff?next=%2F&locale=en");
        verify(event, never()).forwardTo(anyString());
    }

    @Test
    void ownerSeesOnlyTheirOwnTenantEvents() {
        InMemoryAuditTrail trail = new InMemoryAuditTrail();
        trail.record(AuditEvent.of("u1", "u1", "t1", "r1", "a", AuditEvent.Kind.RUN_STARTED, "{}"));
        trail.record(AuditEvent.of("u2", "u2", "t2", "r2", "a", AuditEvent.Kind.RUN_STARTED, "{}"));
        as("owner");
        AuditView v = view(true, trail);
        v.beforeEnter(enter());
        AuditExplorer explorer = walk(v).filter(AuditExplorer.class::isInstance)
                .map(AuditExplorer.class::cast).findFirst().orElseThrow();
        List<AuditEvent> events = explorer.getEvents();
        assertEquals(1, events.size());
        assertTrue(events.stream().allMatch(e -> "u1".equals(e.tenantId())));
    }
}
