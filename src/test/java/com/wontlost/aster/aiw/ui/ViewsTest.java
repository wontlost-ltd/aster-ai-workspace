package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.Page;
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
    private final UI ui = spy(new UI());
    private final Page page = mock(Page.class);
    private final WrappedSession wrapped = mock(WrappedSession.class);
    /** CurrentInstance 以弱引用保存当前会话：必须由测试持有强引用，否则 GC 后 getCurrent() 为空（偶发失败）。 */
    private final VaadinSession session = mock(VaadinSession.class);

    @BeforeEach
    void setUp() {
        System.setProperty(License.HOME_PROPERTY, proHome.toString());
        License.configure(null);
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

    private BeforeEnterEvent enter(String path, QueryParameters query) {
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        when(event.getLocation()).thenReturn(new Location(path, query));
        when(event.getUI()).thenReturn(ui);
        return event;
    }

    /** 每次签发都不同的票据，便于断言「已续签」。 */
    private static final class CountingTicket extends UserTicket {
        private int issued;

        CountingTicket() {
            super("k", Clock.systemUTC());
        }

        @Override
        public String issue(AiwUser user) {
            return "ticket-" + user.userId() + "-" + (++issued);
        }
    }

    private static ChatFactory chats() {
        return new ChatFactory(new CountingTicket(), new AiwI18nProvider());
    }

    private static JsonNode forwarded(AgUiChat chat) {
        return (JsonNode) chat.getElement().getPropertyRaw("forwardedProps");
    }

    private static void expire(AgUiChat chat) {
        ComponentUtil.fireEvent(chat, new AgUiChat.RunErrorEvent(chat, true, "expired", "session_expired"));
    }

    private AgUiChat enterAs(Component view, String path, QueryParameters query) {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(ANN);
        ((com.vaadin.flow.router.BeforeEnterObserver) view).beforeEnter(enter(path, query));
        return find(view, AgUiChat.class).findFirst().orElseThrow();
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

        verify(event, never()).forwardToUrl(anyString());
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
    void workspaceBackLinkGoesToTheCloudDashboardInTheUserLanguage() {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(ANN);
        WorkspaceView view = workspace(false);
        view.beforeEnter(enter("", QueryParameters.empty()));

        assertTrue(find(view, com.vaadin.flow.component.html.Anchor.class)
                .anyMatch(a -> a.getHref().equals("https://cloud.test/zh/dashboard")));
    }

    @Test
    void sessionListNewChatHasAnExplicitAccessibleName() {
        WorkspaceView view = workspace(false);
        enterAs(view, "", QueryParameters.empty());
        SessionHistoryList list = find(view, SessionHistoryList.class).findFirst().orElseThrow();
        assertEquals("新对话", find(list, com.vaadin.flow.component.button.Button.class).findFirst().orElseThrow()
                .getAriaLabel().orElseThrow());
    }

    private static com.vaadin.flow.component.button.Button drawerToggle(Component view) {
        return find(view, com.vaadin.flow.component.button.Button.class)
                .filter(b -> b.hasClassName("aster-drawer-toggle")).findFirst().orElseThrow();
    }

    private static boolean drawerOpen(Component view) {
        return find(view, com.vaadin.flow.component.html.Div.class)
                .filter(d -> d.hasClassName("aster-workspace-body")).findFirst().orElseThrow()
                .hasClassName(ConversationDrawer.OPEN_CLASS);
    }

    @Test
    void narrowScreenDrawerTogglesAndClosesAfterPickingAConversation() {
        WorkspaceView view = workspace(false);
        enterAs(view, "", QueryParameters.empty());
        com.vaadin.flow.component.button.Button toggle = drawerToggle(view);
        assertEquals("对话列表", toggle.getAriaLabel().orElseThrow());
        assertEquals(ConversationDrawer.SIDEBAR_ID, toggle.getElement().getAttribute("aria-controls"));
        assertEquals("false", toggle.getElement().getAttribute("aria-expanded"));
        assertFalse(drawerOpen(view));

        toggle.click();
        assertTrue(drawerOpen(view));
        assertEquals("true", toggle.getElement().getAttribute("aria-expanded"));

        SessionHistoryList list = find(view, SessionHistoryList.class).findFirst().orElseThrow();
        find(list, com.vaadin.flow.component.button.Button.class).findFirst().orElseThrow().click();
        assertFalse(drawerOpen(view), "新建对话后收起抽屉");
        assertEquals("false", toggle.getElement().getAttribute("aria-expanded"));
    }

    @Test
    void backdropClickClosesTheDrawer() {
        WorkspaceView view = workspace(false);
        enterAs(view, "", QueryParameters.empty());
        drawerToggle(view).click();
        com.vaadin.flow.component.html.Div backdrop = find(view, com.vaadin.flow.component.html.Div.class)
                .filter(d -> d.hasClassName("aster-drawer-backdrop")).findFirst().orElseThrow();
        ComponentUtil.fireEvent(backdrop, new com.vaadin.flow.component.ClickEvent<>(backdrop));
        assertFalse(drawerOpen(view));
    }

    @Test
    void proSidebarSelectionClosesTheDrawer() {
        WorkspaceView view = workspace(true);
        enterAs(view, "", QueryParameters.empty());
        drawerToggle(view).click();
        assertTrue(drawerOpen(view));

        ConversationSidebar sidebar = find(view, ConversationSidebar.class).findFirst().orElseThrow();
        ComponentUtil.fireEvent(sidebar, new ConversationSidebar.NewConversationEvent(sidebar, true));
        assertFalse(drawerOpen(view));
    }

    @Test
    void workspaceShowsProSidebarWhenConversationsAreLicensed() {
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(ANN);
        WorkspaceView view = workspace(true);
        view.beforeEnter(enter("", QueryParameters.empty()));

        assertEquals(1, find(view, ConversationSidebar.class).count());
        assertEquals(0, find(view, SessionHistoryList.class).count());
    }

    @Test
    void workspaceRenewsAnExpiredTicketWhileTheUserIsStillSignedIn() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());
        find(view, ModeSelector.class).findFirst().orElseThrow().getContent()
                .setValue(com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode.GENERATE);
        String before = forwarded(chat).path("ticket").asString();

        expire(chat);

        assertNotEquals(before, forwarded(chat).path("ticket").asString());
        assertEquals("generate", forwarded(chat).path("mode").asString(), "续签保留当前模式");
        verify(page, never()).setLocation(anyString());
    }

    @Test
    void workspaceRedirectsToHandoffWhenTheSessionUserIsGone() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());
        String before = forwarded(chat).path("ticket").asString();
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(null);

        expire(chat);

        assertEquals(before, forwarded(chat).path("ticket").asString());
        verify(page).setLocation("https://cloud.test/api/ai-workspace/handoff?next=%2F&locale=zh");
    }

    @Test
    void otherRunErrorsAreIgnored() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());
        String before = forwarded(chat).path("ticket").asString();
        ComponentUtil.fireEvent(chat, new AgUiChat.RunErrorEvent(chat, true, "x", "network"));
        assertEquals(before, forwarded(chat).path("ticket").asString());
    }

    @Test
    void panelRenewsTheTicketServerSideWithoutAskingTheParentToReauth() {
        PanelView view = panel(false);
        AgUiChat chat = enterAs(view, "panel", QueryParameters.of("policyId", "p-1"));
        String before = forwarded(chat).path("ticket").asString();

        expire(chat);

        assertNotEquals(before, forwarded(chat).path("ticket").asString());
        assertEquals("p-1", forwarded(chat).path("policyId").asString());
        verify(page, never()).executeJs("window.asterBridge.reauth()");
        verify(page, never()).setLocation(anyString());
    }

    @Test
    void panelAsksTheParentToReauthOnlyWhenTheSessionUserIsGone() {
        PanelView view = panel(false);
        AgUiChat chat = enterAs(view, "panel", QueryParameters.of("policyId", "p-1"));
        String before = forwarded(chat).path("ticket").asString();
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(null);

        expire(chat);

        assertEquals(before, forwarded(chat).path("ticket").asString());
        verify(page).executeJs("window.asterBridge.reauth()");
        verify(page, never()).setLocation(anyString());
    }

    /** 会话里换成了另一个用户：旧视图不得替新用户续签。 */
    @Test
    void ticketIsNotRenewedForADifferentSessionUser() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());
        String before = forwarded(chat).path("ticket").asString();
        when(wrapped.getAttribute(SessionUser.ATTRIBUTE)).thenReturn(new AiwUser("u2", "Bob", null, "en", "member"));

        expire(chat);

        assertEquals(before, forwarded(chat).path("ticket").asString());
        verify(page).setLocation(anyString());
    }

    private static void stateChanged(AgUiChat chat, String json) {
        var state = (tools.jackson.databind.node.ObjectNode) tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(json);
        ComponentUtil.fireEvent(chat, new AgUiChat.StateChangedEvent(chat, true, state));
    }

    private static long spansWithText(Component view, String text) {
        return find(view, com.vaadin.flow.component.html.Span.class).filter(s -> text.equals(s.getText())).count();
    }

    @Test
    void workspaceShowsTheModelFromTheAgentState() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());
        stateChanged(chat, "{\"validated\":true}");
        assertEquals(0, spansWithText(view, "模型：test-model"));

        stateChanged(chat, "{\"model\":\"test-model\"}");

        assertEquals(1, spansWithText(view, "模型：test-model"));
    }

    @Test
    void panelShowsTheModelFromTheAgentState() {
        PanelView view = panel(false);
        AgUiChat chat = enterAs(view, "panel", QueryParameters.of("policyId", "p-1"));

        stateChanged(chat, "{\"model\":\"test-model\"}");

        assertEquals(1, spansWithText(view, "模型：test-model"));
    }

    private static String status(Component view) {
        StatusLabel label = find(view, StatusLabel.class).findFirst().orElseThrow();
        return label.isVisible() ? label.getText() : null;
    }

    @Test
    void panelShowsValidationOutcomeAndRepairProgress() {
        PanelView view = panel(false);
        AgUiChat chat = enterAs(view, "panel", QueryParameters.of("policyId", "p-1"));
        assertEquals(null, status(view));

        stateChanged(chat, "{\"model\":\"test-model\",\"validationError\":\"bad\",\"repair\":2}");
        assertEquals("修复中（2）…", status(view));

        stateChanged(chat, "{\"validated\":true,\"repair\":2}");
        assertEquals("✓ 已通过校验", status(view));

        stateChanged(chat, "{\"validated\":false,\"validationError\":\"" + "x".repeat(200) + "\"}");
        assertEquals("✗ 未通过校验：" + "x".repeat(StatusLabel.MAX_ERROR) + "…", status(view));

        ComponentUtil.fireEvent(chat, new AgUiChat.RunStartedEvent(chat, true, "policy:p-1", "r2"));
        assertEquals(null, status(view), "每次 run 开始时清空");
    }

    @Test
    void workspaceShowsNotValidatedWithoutDetailAndClearsOnNullState() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());

        stateChanged(chat, "{\"validated\":false}");
        assertEquals("✗ 未通过校验", status(view));

        stateChanged(chat, "{\"validated\":null,\"validationError\":null,\"repair\":null,\"model\":\"m\"}");
        assertEquals(null, status(view));
    }

    @Test
    void workspaceAndPanelShowTheTruncatedNote() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());
        assertFalse(find(view, TruncatedLabel.class).findFirst().orElseThrow().showing());
        stateChanged(chat, "{\"truncated\":true}");
        assertEquals(1, spansWithText(view, "回答已被截断"));
        assertTrue(find(view, TruncatedLabel.class).findFirst().orElseThrow().showing());

        PanelView panel = panel(false);
        AgUiChat panelChat = enterAs(panel, "panel", QueryParameters.of("policyId", "p-1"));
        stateChanged(panelChat, "{\"truncated\":true}");
        assertTrue(find(panel, TruncatedLabel.class).findFirst().orElseThrow().showing());
    }

    @Test
    void validationErrorObjectIsShownAsItsMessage() {
        WorkspaceView view = workspace(false);
        AgUiChat chat = enterAs(view, "", QueryParameters.empty());
        stateChanged(chat, "{\"validated\":false,\"validationError\":{\"message\":\"line 3: bad\"}}");
        assertEquals("✗ 未通过校验：line 3: bad", status(view));
    }

    @Test
    void workspaceTopBarShowsTheWordmarkSectionAndUserInitial() {
        WorkspaceView view = workspace(false);
        enterAs(view, "", QueryParameters.empty());

        assertEquals(1, spansWithText(view, TopBar.WORDMARK));
        assertEquals(1, spansWithText(view, "工作台"));
        assertEquals(1, spansWithText(view, "Ann"));
        assertEquals(1, find(view, com.vaadin.flow.component.html.Span.class)
                .filter(s -> s.hasClassName("aster-avatar") && "A".equals(s.getText())).count());
    }

    @Test
    void initialIsTheFirstCodePointUppercased() {
        assertEquals("A", WorkspaceView.initial("ada"));
        assertEquals("张", WorkspaceView.initial("张三"));
        assertEquals("?", WorkspaceView.initial(" "));
    }

    @Test
    void panelNewChatIsAnIconButtonWithAnAccessibleNameAndTooltip() {
        PanelView view = panel(false);
        enterAs(view, "panel", QueryParameters.of("policyId", "p-1"));

        com.vaadin.flow.component.button.Button newChat =
                find(view, com.vaadin.flow.component.button.Button.class).findFirst().orElseThrow();
        assertEquals("新对话", newChat.getAriaLabel().orElseThrow());
        assertEquals("新对话", newChat.getTooltip().getText());
        assertTrue(find(view, ModeSelector.class).findFirst().orElseThrow().getContent().hasClassName("aster-compact"));
    }
}
