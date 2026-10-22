package com.wontlost.aster.aiw.web;

import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.WrappedSession;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 在首屏 HTML 的 {@code <html>} 上写好亮 / 暗（F29），与 Vaadin {@code Page.setColorScheme} 的写法一致：
 * {@code theme} 属性（Lumo 据此切换）+ 行内 {@code color-scheme}（Aster 令牌的 light-dark() 据此取值）。
 * 服务端渲染即生效，不会先闪一下浅色；之后的站内导航不重载页面，属性一直保留。
 */
@Component
public class ColorSchemeBootstrap implements VaadinServiceInitListener {
    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.addIndexHtmlRequestListener(ColorSchemeBootstrap::apply);
    }

    static void apply(IndexHtmlResponse response) {
        ColorScheme.Value scheme = resolve(response.getVaadinRequest());
        Element html = response.getDocument().head().parent();
        html.attr("theme", scheme.getThemeValue());
        html.attr("style", "color-scheme: " + scheme.getValue() + ";");
    }

    /** 带有效参数时记进会话（必要时新建会话）；否则只读已有会话，不为此新建会话。 */
    static ColorScheme.Value resolve(VaadinRequest request) {
        String param = request.getParameter(ColorSchemePreference.PARAM);
        var remembered = ColorSchemePreference.remembered(param);
        WrappedSession session = request.getWrappedSession(remembered.isPresent());
        remembered.ifPresent(value -> session.setAttribute(ColorSchemePreference.ATTRIBUTE, value));
        Object stored = session == null ? null : session.getAttribute(ColorSchemePreference.ATTRIBUTE);
        return ColorSchemePreference.resolve(param, stored);
    }
}
