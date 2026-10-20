package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.notification.Notification;
import com.wontlost.agui.AgUiChat;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.SessionUser;
import java.util.Optional;

/**
 * run 票据 12 小时过期而页面一直开着（心跳让 Vaadin 会话不过期）时的恢复：运行以 {@code session_expired} 失败后，
 * 会话里仍是同一用户 → 服务端续签票据并提示重发；用户已不在（或已换人）→ 交给视图的退出处理
 * （工作台整页跳 cloud 重签；侧栏请父页面重建 iframe）。两条路径互斥，侧栏不会既续签又重建。
 */
final class TicketExpiry {
    static final String CODE = "session_expired";
    static final String RENEWED_KEY = "ui.ticketRenewed";
    private static final int NOTICE_MS = 5000;

    private TicketExpiry() {}

    static void watch(AgUiChat chat, AiwUser owner, ChatFactory chats, String renewedText, Runnable signedOut) {
        chat.addRunErrorListener(e -> {
            if (!CODE.equals(e.getCode())) {
                return;
            }
            Optional<AiwUser> current = SessionUser.current().filter(u -> u.userId().equals(owner.userId()));
            if (current.isEmpty()) {
                signedOut.run();
                return;
            }
            chats.renewTicket(chat, current.get());
            Notification.show(renewedText, NOTICE_MS, Notification.Position.TOP_CENTER);
        });
    }
}
