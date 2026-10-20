package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.server.VaadinSession;
import com.wontlost.agui.AgUiChat;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.pro.conversations.Conversation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 无 Pro 会话授权时的历史（ADR 0049 §2.4/§2.8）：存于 VaadinSession 属性 {@value #ATTRIBUTE}，
 * 形如 {@code Map<userId, Map<threadId, List<Message>>>}，仅当前会话内可读。按用户分区：同一浏览器换号重新交接时，
 * 即便会话仍在，也读不到上一位用户的线程（交接控制器另会作废旧会话，双重保护）。
 * 实例只代表一位用户的分区；工作台与侧栏 iframe 共享同一会话，两个 UI 都在会话锁下访问。
 * 每位用户按最近保存排序，最多保留 {@value #MAX_THREADS} 个线程以限制内存。
 */
public final class SessionHistory {
    public static final String ATTRIBUTE = "aiw.history";
    static final int MAX_THREADS = 50;

    private final Map<String, List<Message>> threads;

    public SessionHistory() {
        this(new LinkedHashMap<>());
    }

    private SessionHistory(Map<String, List<Message>> threads) {
        this.threads = threads;
    }

    /** 取（必要时创建）会话里某位用户的历史分区。 */
    @SuppressWarnings("unchecked")
    public static SessionHistory of(VaadinSession session, String userId) {
        Map<String, Map<String, List<Message>>> users =
                (Map<String, Map<String, List<Message>>>) session.getAttribute(ATTRIBUTE);
        if (users == null) {
            users = new LinkedHashMap<>();
            session.setAttribute(ATTRIBUTE, users);
        }
        return new SessionHistory(users.computeIfAbsent(userId, id -> new LinkedHashMap<>()));
    }

    public static SessionHistory current(String userId) {
        return of(VaadinSession.getCurrent(), userId);
    }

    /** 保存一次运行结束时客户端回传的完整对话；空对话不记。 */
    public void save(String threadId, List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        threads.remove(threadId);
        threads.put(threadId, List.copyOf(messages));
        Iterator<String> oldest = threads.keySet().iterator();
        while (threads.size() > MAX_THREADS) {
            oldest.next();
            oldest.remove();
        }
    }

    public Optional<List<Message>> find(String threadId) {
        return Optional.ofNullable(threads.get(threadId));
    }

    /** 把某线程的历史放回组件；没有记录时保持组件为空。 */
    public void restore(AgUiChat chat, String threadId) {
        find(threadId).ifPresent(chat::setMessages);
    }

    /** 最近保存的在前。 */
    public List<String> threadIds() {
        List<String> ids = new ArrayList<>(threads.keySet());
        Collections.reverse(ids);
        return ids;
    }

    /** 列表标题与 Pro 侧栏一致：第一条用户消息。 */
    public String title(String threadId) {
        return Conversation.deriveTitle(threads.getOrDefault(threadId, List.of()));
    }
}
