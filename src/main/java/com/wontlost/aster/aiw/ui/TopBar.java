package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;

/** 工作台与审计页共用的顶栏：左侧紫色「Aster AI」字标 + 栏目名，右侧依次放入给定组件。 */
final class TopBar {
    /** 字标是品牌名，不随界面语言翻译。 */
    static final String WORDMARK = "Aster AI";

    private TopBar() {}

    static Div of(String section, Component... trailing) {
        H1 brand = new H1(span(WORDMARK, "aster-wordmark"), span(section, "aster-brand-section"));
        brand.addClassName("aster-brand");
        Div spacer = new Div();
        spacer.addClassName("aster-topbar-spacer");
        Div bar = new Div(brand, spacer);
        bar.add(trailing);
        bar.addClassName("aster-topbar");
        return bar;
    }

    /** 文字按钮外观的返回链接（左箭头 + 文案），仍是普通链接，目标不变。 */
    static Anchor backLink(String href, String text) {
        Anchor back = new Anchor(href, VaadinIcon.ARROW_LEFT.create(), new Span(text));
        back.addClassName("aster-link-button");
        return back;
    }

    static Span span(String text, String className) {
        Span span = new Span(text);
        span.addClassName(className);
        return span;
    }
}
