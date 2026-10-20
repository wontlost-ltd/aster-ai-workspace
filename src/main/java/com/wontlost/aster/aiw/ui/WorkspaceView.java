package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.wontlost.agui.AgUiChat;
import com.wontlost.agui.model.ContextItem;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.ConversationBinder;
import com.wontlost.agui.pro.conversations.ConversationSidebar;
import com.wontlost.agui.pro.conversations.ConversationSidebarI18n;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.HandoffUrls;
import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.i18n.I18nBeans;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.util.List;
import java.util.function.Function;

/**
 * 工作台（{@code /}，ADR 0049 §2.1）：左栏为 Pro 会话侧栏（已授权）或本会话历史，右侧为聊天。
 * 默认「问答」；「生成 / 建议」可配合粘贴的策略源码（作为 {@code policy.source} 上下文）。
 */
@Route("")
public class WorkspaceView extends VerticalLayout implements BeforeEnterObserver {
    private static final String ASIDE_WIDTH = "18rem";

    private final ChatFactory chats;
    private final AiwI18nProvider i18n;
    private final ProFeatures pro;
    private final ConversationStore store;
    private final AuditTrail trail;
    private final AiwProperties props;

    public WorkspaceView(ChatFactory chats, AiwI18nProvider i18n, ProFeatures pro, ConversationStore store,
            AuditTrail trail, AiwProperties props) {
        this.chats = chats;
        this.i18n = i18n;
        this.pro = pro;
        this.store = store;
        this.trail = trail;
        this.props = props;
        setSizeFull();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (getComponentCount() > 0) {
            return;
        }
        SignIn.userOrRedirect(event, props.cloudUrl()).ifPresent(this::build);
    }

    private void build(AiwUser user) {
        Function<String, String> texts = key -> i18n.text(user.locale(), key);
        AgUiChat chat = chats.create(user, Mode.ASK, null, false);
        HistoryBinding history = HistoryBinding.bind(chat, user, chat.getThreadId(), pro, store, trail);
        TicketExpiry.watch(chat, user, chats, texts.apply(TicketExpiry.RENEWED_KEY), 
                () -> UI.getCurrent().getPage().setLocation(HandoffUrls.rehandoff(props.cloudUrl(), "/", user.locale())));
        Component aside = history.conversations()
                .map(binder -> sidebar(binder, texts))
                .orElseGet(() -> sessionList(chat, history, texts));
        aside.getElement().getStyle().set("flex", "0 0 " + ASIDE_WIDTH);

        HorizontalLayout body = new HorizontalLayout(aside, conversation(chat, texts));
        body.setSizeFull();
        add(header(user, texts), body);
        setFlexGrow(1, body);
    }

    private Component header(AiwUser user, Function<String, String> texts) {
        H1 title = new H1(texts.apply("workspace.title"));
        title.getStyle().set("font-size", "var(--lumo-font-size-xl)").set("margin", "0");
        Span who = new Span(displayName(user));
        Anchor back = new Anchor(props.cloudUrl(), texts.apply("workspace.back"));
        HorizontalLayout header = new HorizontalLayout(title, who, back);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.BASELINE);
        header.expand(who);
        return header;
    }

    /** 模式 + （生成/建议时）粘贴策略 + 聊天。 */
    private Component conversation(AgUiChat chat, Function<String, String> texts) {
        TextArea paste = new TextArea(texts.apply("workspace.pastePolicy"));
        paste.setHelperText(texts.apply("workspace.pastePolicyHelper"));
        paste.setWidthFull();
        paste.setMaxHeight("12rem");
        paste.setVisible(false);
        paste.addValueChangeListener(e -> chat.setContext(List.of(new ContextItem("policy.source", e.getValue()))));

        ModeSelector modes = new ModeSelector(Mode.ASK, texts);
        modes.addModeChangeListener(mode -> {
            ChatFactory.switchMode(chat, mode);
            paste.setVisible(mode != Mode.ASK);
        });
        chat.setSizeFull();
        VerticalLayout column = new VerticalLayout(modes, paste, chat);
        column.setPadding(false);
        column.setSizeFull();
        column.setFlexGrow(1, chat);
        return column;
    }

    private static Component sidebar(ConversationBinder binder, Function<String, String> texts) {
        ConversationSidebar sidebar = new ConversationSidebar();
        sidebar.setI18n(I18nBeans.fill(new ConversationSidebarI18n(), "sidebar.", texts));
        binder.withSidebar(sidebar);
        return sidebar;
    }

    private static Component sessionList(AgUiChat chat, HistoryBinding history, Function<String, String> texts) {
        return new SessionHistoryList(history.sessionHistory().orElseThrow(), history, chat, texts);
    }

    private static String displayName(AiwUser user) {
        if (user.name() != null && !user.name().isBlank()) {
            return user.name();
        }
        return user.email() != null ? user.email() : user.userId();
    }
}
