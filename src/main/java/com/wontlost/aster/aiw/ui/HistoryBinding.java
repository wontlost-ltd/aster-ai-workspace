package com.wontlost.aster.aiw.ui;

import com.wontlost.agui.AgUiChat;
import com.wontlost.agui.pro.audit.AuditBinder;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.agui.pro.conversations.ConversationBinder;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.agui.pro.conversations.ConversationStore;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.pro.ProFeatures;
import com.wontlost.aster.aiw.pro.ResilientAuditTrail;
import com.wontlost.aster.aiw.pro.ResilientConversationStore;
import java.util.Optional;

/**
 * 把聊天组件接到历史：Pro 会话已授权 → ConversationBinder（库存储，跨会话可读）；否则 → {@link SessionHistory}。
 * 审计同理仅在 Pro 审计已授权时绑定。两条路径都以运行结束时客户端回传的消息列表为准
 * （suggest / ask 没有 MESSAGES_SNAPSHOT，裁定 R12）。
 *
 * <p>Pro 存储与审计都包上容错层：数据库不可用时对话照常，仅提示「未能保存」（ADR 0049 §2.7）。
 * 作用域按 cloud 身份模型：租户 = 拥有者 = 用户 id。
 */
public final class HistoryBinding {
    static final String AGENT_ID = "aster";

    private final AgUiChat chat;
    private final ConversationBinder conversations;
    private final SessionHistory session;

    private HistoryBinding(AgUiChat chat, ConversationBinder conversations, SessionHistory session) {
        this.chat = chat;
        this.conversations = conversations;
        this.session = session;
    }

    /** 视图入口：会话历史取自当前 VaadinSession 中该用户的分区，保存失败提示挂在当前 UI 上。 */
    public static HistoryBinding bind(AgUiChat chat, AiwUser user, String threadId, ProFeatures pro,
            ConversationStore store, AuditTrail trail) {
        return bind(chat, user, threadId, pro, store, trail, SessionHistory.current(user.userId()), SaveFailedNotice.forCurrentUi());
    }

    static HistoryBinding bind(AgUiChat chat, AiwUser user, String threadId, ProFeatures pro,
            ConversationStore store, AuditTrail trail, SessionHistory history, Runnable onSaveFailed) {
        chat.setThreadId(threadId);
        if (pro.enabled(AuditBinder.FEATURE)) {
            AuditBinder.bind(chat, new ResilientAuditTrail(trail), user.userId(), user::userId, () -> AGENT_ID);
        }
        if (pro.enabled(ConversationBinder.FEATURE)) {
            ConversationScope scope = new ConversationScope(user.userId(), user.userId());
            ConversationBinder binder = ConversationBinder.bind(
                    chat, new ResilientConversationStore(store, onSaveFailed), scope, () -> AGENT_ID);
            binder.restore(threadId);
            return new HistoryBinding(chat, binder, null);
        }
        history.restore(chat, threadId);
        chat.addRunFinishedListener(e -> history.save(e.getThreadId(), e.getMessages()));
        return new HistoryBinding(chat, null, history);
    }

    /** Pro 会话绑定器（工作台据此挂侧栏）；未授权时为空。 */
    public Optional<ConversationBinder> conversations() {
        return Optional.ofNullable(conversations);
    }

    /** 会话内存历史；Pro 会话已授权时为空。 */
    public Optional<SessionHistory> sessionHistory() {
        return Optional.ofNullable(session);
    }

    /** 新对话：新 threadId、空历史。 */
    public void newConversation() {
        if (conversations != null) {
            conversations.newConversation();
            return;
        }
        chat.clear();
    }

    /** 切到会话内存里的某个线程（Pro 侧栏自行处理选择，不走这里）。 */
    public void restore(String threadId) {
        if (conversations != null) {
            conversations.restore(threadId);
            return;
        }
        chat.stop();
        chat.setThreadId(threadId);
        session.restore(chat, threadId);
    }
}
