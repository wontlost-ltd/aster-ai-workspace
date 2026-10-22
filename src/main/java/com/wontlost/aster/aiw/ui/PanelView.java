package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.wontlost.agui.AgUiChat;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.util.function.Function;

/**
 * 编辑器侧栏（{@code /panel?policyId=}，ADR 0049 §2.1/§2.5）：以 iframe 嵌在 cloud 策略编辑器右栏。
 *
 * <p>默认线程 {@code policy:{policyId}}、默认模式「建议」；策略上下文与写回经浏览器侧的
 * {@code aster-bridge.ts} 与父页面 postMessage 交换，服务端不参与；票据过期由 {@link TicketExpiry} 处理。
 */
@Route("panel")
@JsModule("./aster-bridge.ts")
public class PanelView extends VerticalLayout implements BeforeEnterObserver {
    private final ChatFactory chats;
    private final AiwI18nProvider i18n;
    private final ProFeatures pro;
    private final ConversationStore store;
    private final AuditTrail trail;
    private final AiwProperties props;

    public PanelView(ChatFactory chats, AiwI18nProvider i18n, ProFeatures pro, ConversationStore store,
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
        addClassName("aster-panel");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (getComponentCount() > 0) {
            return;
        }
        String policyId = PanelParams.policyId(
                event.getLocation().getQueryParameters().getSingleParameter("policyId"));
        SignIn.userOrRedirect(event, props.cloudUrl()).ifPresent(user -> build(user, policyId));
    }

    private void build(AiwUser user, String policyId) {
        Function<String, String> texts = key -> i18n.text(user.locale(), key);
        AgUiChat chat = chats.create(user, Mode.SUGGEST, policyId, true);
        HistoryBinding history = HistoryBinding.bind(chat, user, PanelParams.threadId(policyId), pro, store, trail);
        // 会话用户已不在时才请父页面重建 iframe 重新交接；仍在则服务端续签，不重载
        TicketExpiry.watch(chat, user, chats, texts.apply(TicketExpiry.RENEWED_KEY),
                () -> UI.getCurrent().getPage().executeJs("window.asterBridge.reauth()"));

        ModeSelector modes = new ModeSelector(Mode.SUGGEST, texts).compact();
        modes.addModeChangeListener(mode -> ChatFactory.switchMode(chat, mode));
        String newChatText = texts.apply("panel.newChat");
        Button newChat = new Button(VaadinIcon.PLUS.create(), e -> history.newConversation());
        newChat.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_SMALL);
        newChat.setAriaLabel(newChatText);
        newChat.setTooltipText(newChatText);
        // 第一行：模式分段（空间不足时横向滚动）+ 右侧「新对话」图标按钮
        Div bar = new Div(modes, newChat);
        bar.addClassName("aster-panel-bar");
        // 第二行：模型与校验徽标，按整颗徽标换行；都隐藏时整行收起
        Div badges = new Div(new ModelLabel(chat, texts.apply(ModelLabel.KEY)), new StatusLabel(chat, texts),
                new TruncatedLabel(chat, texts.apply(TruncatedLabel.KEY)));
        badges.addClassName("aster-badges");

        add(bar, badges, chat);
        setFlexGrow(1, chat);
        getElement().executeJs("window.asterBridge.init($0, $1, $2)", chat.getElement(), props.cloudOrigin(), policyId);
    }
}
