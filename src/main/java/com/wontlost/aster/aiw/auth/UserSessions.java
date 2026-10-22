package com.wontlost.aster.aiw.auth;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 本实例上每个用户的 HTTP 会话登记（交接写入 {@link SessionUser#ATTRIBUTE} 时登记，会话销毁时移除），
 * 供 GDPR 清除后立即作废该用户打开着的工作台：作废后页面的下一次请求即失效，不会再回写对话。
 *
 * <p>只覆盖本实例；多副本时其它实例上的会话由 aiw_erased_message 墓碑兜底。
 */
@Component
public class UserSessions implements HttpSessionListener, HttpSessionAttributeListener {
    private final Map<String, Set<HttpSession>> byUser = new ConcurrentHashMap<>();

    @Override
    public void attributeAdded(HttpSessionBindingEvent event) {
        track(event);
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent event) {
        // 事件里的值是被替换掉的旧值：先移出旧用户，再登记会话里的新值
        if (SessionUser.ATTRIBUTE.equals(event.getName()) && event.getValue() instanceof AiwUser old) {
            forget(old.userId(), event.getSession());
        }
        track(event);
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent event) {
        if (SessionUser.ATTRIBUTE.equals(event.getName()) && event.getValue() instanceof AiwUser u) {
            forget(u.userId(), event.getSession());
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        byUser.values().forEach(set -> set.remove(session));
        byUser.values().removeIf(Set::isEmpty);
    }

    /** 作废该用户在本实例上的全部会话，返回作废个数。已失效的会话忽略。 */
    public int invalidate(String userId) {
        Set<HttpSession> sessions = byUser.remove(userId);
        if (sessions == null) {
            return 0;
        }
        int count = 0;
        for (HttpSession session : sessions) {
            try {
                session.invalidate();
                count++;
            } catch (IllegalStateException alreadyInvalid) {
                // 会话已在别处失效
            }
        }
        return count;
    }

    int tracked(String userId) {
        return byUser.getOrDefault(userId, Set.of()).size();
    }

    private void track(HttpSessionBindingEvent event) {
        if (SessionUser.ATTRIBUTE.equals(event.getName())
                && event.getSession().getAttribute(SessionUser.ATTRIBUTE) instanceof AiwUser u) {
            byUser.computeIfAbsent(u.userId(), k -> ConcurrentHashMap.newKeySet()).add(event.getSession());
        }
    }

    private void forget(String userId, HttpSession session) {
        byUser.computeIfPresent(userId, (k, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
    }
}
