package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.wontlost.agui.pro.audit.AuditBinder;
import com.wontlost.agui.pro.audit.AuditExplorer;
import com.wontlost.agui.pro.audit.AuditExplorerI18n;
import com.wontlost.agui.pro.audit.AuditTrail;
import com.wontlost.aster.aiw.auth.AiwUser;
import com.wontlost.aster.aiw.config.AiwProperties;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import com.wontlost.aster.aiw.i18n.I18nBeans;
import com.wontlost.aster.aiw.pro.ProFeatures;
import java.util.Set;
import java.util.function.Function;

/**
 * 审计浏览器（{@code /audit}，ADR 0049 §2.7）：仅 owner / admin 且 audit 已授权可见，其余回到 {@code /}。
 * 数据按用户租户（cloud 中租户即用户 id）限定，不跨租户。
 */
@Route("audit")
public class AuditView extends VerticalLayout implements BeforeEnterObserver {
    private static final Set<String> ROLES = Set.of("owner", "admin");

    private final AiwI18nProvider i18n;
    private final ProFeatures pro;
    private final AuditTrail trail;
    private final AiwProperties props;

    public AuditView(AiwI18nProvider i18n, ProFeatures pro, AuditTrail trail, AiwProperties props) {
        this.i18n = i18n;
        this.pro = pro;
        this.trail = trail;
        this.props = props;
        setSizeFull();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (getComponentCount() > 0) {
            return;
        }
        SignIn.userOrRedirect(event, props.cloudUrl()).ifPresent(user -> {
            if (allowed(user)) {
                build(user);
            } else {
                event.forwardTo("");
            }
        });
    }

    private boolean allowed(AiwUser user) {
        return ROLES.contains(user.role()) && pro.enabled(AuditBinder.FEATURE);
    }

    private void build(AiwUser user) {
        Function<String, String> texts = key -> i18n.text(user.locale(), key);
        AuditExplorer explorer = new AuditExplorer(trail, user::userId, () -> null);
        explorer.setI18n(I18nBeans.fill(new AuditExplorerI18n(), "audit.", texts));

        H1 title = new H1(texts.apply("audit.title"));
        title.getStyle().set("font-size", "var(--lumo-font-size-xl)").set("margin", "0");
        Anchor back = new Anchor("", texts.apply("audit.back"));
        HorizontalLayout header = new HorizontalLayout(title, back);
        header.setWidthFull();
        header.setAlignItems(Alignment.BASELINE);
        header.expand(title);
        add(header, explorer);
        setFlexGrow(1, explorer);
    }
}
