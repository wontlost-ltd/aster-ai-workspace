package com.wontlost.aster.aiw.auth;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;

/**
 * 当前用户在 HTTP 会话中的存取点（属性名 {@value #ATTRIBUTE}）。
 *
 * <p>交接控制器用 {@link #set} 写入 HttpSession；Vaadin 视图在 UI 线程里用 {@link #current()} 读取，
 * VaadinSession 包装的正是同一个 HttpSession，因此两边看到同一份属性。
 */
public final class SessionUser {
    public static final String ATTRIBUTE = "aiw.user";

    private SessionUser() {}

    /** 当前 Vaadin 会话里的用户；不在 Vaadin 请求/UI 线程内或尚未交接时为空。 */
    public static Optional<AiwUser> current() {
        VaadinSession vaadin = VaadinSession.getCurrent();
        WrappedSession wrapped = vaadin == null ? null : vaadin.getSession();
        Object user = wrapped == null ? null : wrapped.getAttribute(ATTRIBUTE);
        return user instanceof AiwUser u ? Optional.of(u) : Optional.empty();
    }

    public static void set(HttpSession session, AiwUser user) {
        session.setAttribute(ATTRIBUTE, user);
    }
}
