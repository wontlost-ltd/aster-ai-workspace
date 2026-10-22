package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
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
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.HandoffUrls;
import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 工作台（{@code /}，ADR 0049 §2.1）：顶栏 + 左栏（Pro 会话侧栏或本会话历史）+ 居中的聊天列。
 * 默认「问答」；「生成 / 建议」可配合粘贴的策略源码（作为 {@code policy.source} 上下文）。
 */
@Route("")
public class WorkspaceView extends VerticalLayout implements BeforeEnterObserver {
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
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(Alignment.STRETCH);
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
        Div aside = new Div();
        aside.addClassName("aster-sidebar");
        Div main = new Div(conversation(chat, texts));
        main.addClassName("aster-main");
        Div body = new Div(aside, main);
        body.addClassName("aster-workspace-body");
        // 窄屏下左栏是抽屉：选中或新建对话后收起，让聊天占满全宽
        ConversationDrawer drawer = new ConversationDrawer(body, aside, texts.apply("workspace.conversations"), this);
        aside.add(history.conversations()
                .map(binder -> sidebar(binder, texts, drawer::close))
                .orElseGet(() -> sessionList(chat, history, texts, drawer::close)));

        Div top = header(user, texts);
        top.addComponentAsFirst(drawer.toggle());
        add(top, body);
        setFlexGrow(1, body);
    }

    /** 顶栏：字标 + 栏目名，右侧为用户头像与「返回 Aster Cloud」。 */
    private Div header(AiwUser user, Function<String, String> texts) {
        String name = displayName(user);
        Span avatar = TopBar.span(initial(name), "aster-avatar");
        // 名字紧挨着显示，头像只是装饰，读屏跳过
        avatar.getElement().setAttribute("aria-hidden", "true");
        Span who = new Span(avatar, TopBar.span(name, "aster-user-name"));
        who.addClassName("aster-user");
        return TopBar.of(texts.apply("workspace.section"), who,
                TopBar.backLink(HandoffUrls.dashboard(props.cloudUrl(), user.locale()), texts.apply("workspace.back")));
    }

    /** 居中的对话列：工具条（左模式、右模型与校验徽标）+ （生成/建议时）粘贴策略 + 聊天。 */
    private Component conversation(AgUiChat chat, Function<String, String> texts) {
        TextArea paste = new TextArea(texts.apply("workspace.pastePolicy"));
        paste.setHelperText(texts.apply("workspace.pastePolicyHelper"));
        paste.addClassName("aster-paste");
        paste.setWidthFull();
        paste.setMaxHeight("12rem");
        paste.setVisible(false);
        paste.addValueChangeListener(e -> chat.setContext(List.of(new ContextItem("policy.source", e.getValue()))));

        ModeSelector modes = new ModeSelector(Mode.ASK, texts);
        modes.addModeChangeListener(mode -> {
            ChatFactory.switchMode(chat, mode);
            paste.setVisible(mode != Mode.ASK);
        });
        Div badges = new Div(new ModelLabel(chat, texts.apply(ModelLabel.KEY)), new StatusLabel(chat, texts),
                new TruncatedLabel(chat, texts.apply(TruncatedLabel.KEY)));
        badges.addClassName("aster-badges");
        Div toolbar = new Div(modes, badges);
        toolbar.addClassName("aster-toolbar");
        Div column = new Div(toolbar, paste, chat);
        column.addClassName("aster-column");
        return column;
    }

    private static Component sidebar(ConversationBinder binder, Function<String, String> texts, Runnable picked) {
        ConversationSidebar sidebar = new LabelledConversationSidebar(texts);
        binder.withSidebar(sidebar);
        sidebar.addConversationSelectedListener(e -> picked.run());
        sidebar.addNewConversationListener(e -> picked.run());
        return sidebar;
    }

    private static Component sessionList(AgUiChat chat, HistoryBinding history, Function<String, String> texts,
            Runnable picked) {
        return new SessionHistoryList(history.sessionHistory().orElseThrow(), history, chat, texts, picked);
    }

    /** 头像只显示名字的首字（按码点取，兼容中文与天城文），大写。 */
    static String initial(String name) {
        return name.isBlank() ? "?" : new String(Character.toChars(name.codePointAt(0))).toUpperCase(Locale.ROOT);
    }

    private static String displayName(AiwUser user) {
        if (user.name() != null && !user.name().isBlank()) {
            return user.name();
        }
        return user.email() != null ? user.email() : user.userId();
    }
}
