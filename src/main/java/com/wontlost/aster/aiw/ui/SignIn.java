package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.auth.HandoffUrls;
import com.wontlost.aster.aiw.auth.SessionUser;
import java.util.Locale;
import java.util.Optional;

/**
 * 视图的身份前置（Task 11 合约）：会话里没有用户 → 整页跳回 cloud 重签交接令牌，next 为当前路径与查询；
 * 有用户 → 把 UI 语言设为用户的界面语言。
 */
final class SignIn {
    private SignIn() {}

    static Optional<AiwUser> userOrRedirect(BeforeEnterEvent event, String cloudUrl) {
        Optional<AiwUser> user = SessionUser.current();
        if (user.isEmpty()) {
            String next = "/" + event.getLocation().getPathWithQueryParameters();
            String language = event.getUI().getLocale().getLanguage();
            event.forwardToUrl(HandoffUrls.rehandoff(cloudUrl, next, language));
            return user;
        }
        event.getUI().setLocale(Locale.forLanguageTag(user.get().locale()));
        return user;
    }
}
