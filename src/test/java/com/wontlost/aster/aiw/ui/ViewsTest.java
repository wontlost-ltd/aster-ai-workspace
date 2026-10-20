package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import com.wontlost.agui.AgUiChat;
import com.wontlost.agui.pro.audit.InMemoryAuditTrail;
import com.wontlost.agui.pro.conversations.ConversationSidebar;
import com.wontlost.agui.pro.conversations.InMemoryConversationStore;
import com.wontlost.agui.pro.license.License;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.SessionUser;
import com.wontlost.aster.aiw.auth.UserTicket;
import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** 视图冒烟（无浏览器）：登录跳转、侧栏默认线程与模式、工作台左栏按授权切换。 */
class ViewsTest {
    private static final AiwProperties PROPS =
            new AiwProperties("https://cloud.test", "https://cloud.test", "h", "k", "t", 180);
    private static final AiwUser ANN = new AiwUser("u1", "Ann", null, "zh", "member");

    @TempDir Path proHome;
    private final UI ui = new UI();
    private final WrappedSession wrapped = mock(WrappedSession.class);

    @BeforeEach
    void setUp() {
        System.setProperty(License.HOME_PROPERTY, proHome.toString());
        License.configure(null);
        VaadinSession session = mock(VaadinSession.class);
        when(session.getSession()).thenReturn(wrapped);
        VaadinSession.setCurrent(session);
        UI.setCurrent(ui);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        VaadinSession.setCurrent(null);
        System.clearProperty(License.HOME_PROPERTY);
        License.configure(null);
    }

    private BeforeEnterEvent enter(String path, QueryParameters query) {
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        when(event.getLocation()).thenReturn(new Location(path, query));
        when(event.getUI()).thenReturn(ui);
        return event;
    }

    private static ChatFactory chats() {
        return new ChatFactory(new UserTicket("k", Clock.systemUTC()), new AiwI18nProvider());
    }

    private static PanelView panel(boolean pro) {
        return new PanelView(chats(), new AiwI18nProvider(), new ProFeatures(f -> pro),
                new InMemoryConversationStore(), new InMemoryAuditTrail(), PROPS);
    }

    private static WorkspaceView workspace(boolean pro) {
        return new WorkspaceView(chats(), new AiwI18nProvider(), new ProFeatures(f -> pro),
                new InMemoryConversationStore(), new InMemoryAuditTrail(), PROPS);
    }

    private static <T extends Component> Stream<T> find(Component root, Class<T> type) {
        Stream<T> self = type.isInstance(root) ? Stream.of(type.cast(root)) : Stream.empty();
        return Stream.concat(self, root.getChildren().flatMap(c -> find(c, type)));
    }

    @Test
    void withoutSessionUserThePanelRedirectsToCloudHandoff() {
        ui.setLocale(Locale.GERMAN);
        BeforeEnterEvent event = enter("panel", QueryParameters.of("policyId", "p-1"));
        PanelView view = panel(false);
        view.beforeEnter(event);

        verify(event).forwardToUrl(
                "https://cloud.test/api/ai-workspace/handoff?next=%2Fpanel%3FpolicyId%3Dp-1&locale=de");
        assertEquals(0, view.getComponentCount());
    }

    @Test
    void panelUsesThePolicyThreadSuggestModeAndUserLocale() {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(ANN);
        BeforeEnterEvent event = enter("panel", QueryParameters.of("policyId", "p-1"));
        PanelView view = panel(false);
        view.beforeEnter(event);

        verify(event, never()).forwardToUrl(org.mockito.ArgumentMatchers.anyString());
        assertEquals(Locale.forLanguageTag("zh"), ui.getLocale());
        AgUiChat chat = find(view, AgUiChat.class).findFirst().orElseThrow();
        assertEquals("policy:p-1", chat.getThreadId());
        JsonNode props = (JsonNode) chat.getElement().getPropertyRaw("forwardedProps");
        assertEquals("suggest", props.path("mode").asString());
        assertTrue(chat.getThemeNames().contains("compact"));
    }

    @Test
    void invalidPolicyIdFallsBackToNewThread() {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(ANN);
        PanelView view = panel(false);
        view.beforeEnter(enter("panel", QueryParameters.of("policyId", "../x")));
        assertEquals("policy:new", find(view, AgUiChat.class).findFirst().orElseThrow().getThreadId());
    }

    @Test
    void workspaceDefaultsToAskWithSessionListWithoutPro() {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(ANN);
        WorkspaceView view = workspace(false);
        view.beforeEnter(enter("", QueryParameters.empty()));

        AgUiChat chat = find(view, AgUiChat.class).findFirst().orElseThrow();
        assertEquals("ask", ((JsonNode) chat.getElement().getPropertyRaw("forwardedProps")).path("mode").asString());
        assertEquals(1, find(view, SessionHistoryList.class).count());
        assertEquals(0, find(view, ConversationSidebar.class).count());
    }

    @Test
    void workspaceShowsProSidebarWhenConversationsAreLicensed() {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(ANN);
        WorkspaceView view = workspace(true);
        view.beforeEnter(enter("", QueryParameters.empty()));

        assertEquals(1, find(view, ConversationSidebar.class).count());
        assertEquals(0, find(view, SessionHistoryList.class).count());
    }
}
