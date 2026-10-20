package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.ComponentUtil;
import com.wontlost.agui.AgUiChat;
import com.wontlost.agui.AgUiChatI18n;
import com.wontlost.agui.AgUiChatVariant;
import com.wontlost.agui.AgUiCodeAction;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.UserTicket;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.i18n.I18nBeans;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按用户与场景创建指向 AsterAgent（{@code /agui/aster}）的聊天组件。
 *
 * <p>编辑器侧栏（compact）给代码块提供 复制 / 插入 / 替换；工作台只有复制。
 * {@code generate} 从不自动写回编辑器，写回只发生在用户点击代码块按钮时（ADR 0049 §2.5）。
 */
@Component
public class ChatFactory {
    static final String AGENT_URL = "/agui/aster";
    private static final AgUiCodeAction[] PANEL_ACTIONS = {
        AgUiCodeAction.COPY, AgUiCodeAction.INSERT, AgUiCodeAction.REPLACE
    };

    private final UserTicket tickets;
    private final AiwI18nProvider i18n;

    public ChatFactory(UserTicket tickets, AiwI18nProvider i18n) {
        this.tickets = tickets;
        this.i18n = i18n;
    }

    /** 票据未配置时 {@link UserTicket#issue} 抛 IllegalStateException：属部署错误，交给 Vaadin 错误页。 */
    public AgUiChat create(AiwUser user, Mode mode, String policyId, boolean compact) {
        AgUiChat chat = new AgUiChat(AGENT_URL);
        chat.setI18n(I18nBeans.fill(new AgUiChatI18n(), "chat.", key -> i18n.text(user.locale(), key)));
        chat.setCodeActions(compact ? PANEL_ACTIONS : new AgUiCodeAction[] {AgUiCodeAction.COPY});
        if (compact) {
            chat.addThemeVariants(AgUiChatVariant.COMPACT);
        }
        ComponentUtil.setData(chat, ChatProps.class,
                new ChatProps(tickets.issue(user), policyId, suggestions(user.locale())));
        switchMode(chat, mode);
        return chat;
    }

    /** 切换模式：重建 forwardedProps（保留票据与策略 id）并换上该模式的开场建议。 */
    public static void switchMode(AgUiChat chat, Mode mode) {
        ChatProps props = ComponentUtil.getData(chat, ChatProps.class);
        chat.setForwardedProps(props.forwardedProps(mode));
        chat.setSuggestions(props.suggestionsFor(mode));
    }

    private Map<Mode, List<String>> suggestions(String locale) {
        Map<Mode, List<String>> byMode = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            String prefix = "suggest." + mode.wire() + ".";
            byMode.put(mode, List.of(i18n.text(locale, prefix + 1), i18n.text(locale, prefix + 2)));
        }
        return byMode;
    }
}
