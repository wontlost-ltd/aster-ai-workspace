package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * 窄屏（≤ 48rem）下工作台左栏的抽屉开合：顶栏多出一颗「对话列表」开关，左栏收到屏幕外，
 * 打开时以浮层盖在聊天上方并带遮罩，聊天占满全宽。宽屏下开关与遮罩由 CSS 隐藏、左栏常驻，
 * 开合状态不影响版式。
 *
 * <p>状态只是工作台主体上的 {@code aster-drawer-open} 类与开关的 {@code aria-expanded}；
 * 选中 / 新建对话、点遮罩或按 Esc 都会收起。
 */
final class ConversationDrawer {
    static final String OPEN_CLASS = "aster-drawer-open";
    static final String SIDEBAR_ID = "aster-conversations";

    private final Div body;
    private final Button toggle;
    private boolean open;

    /**
     * @param body  工作台主体（含左栏与聊天列）；遮罩追加为它的子元素
     * @param aside 左栏；赋予 id 供开关的 aria-controls 引用
     * @param label 开关的本地化名称
     */
    ConversationDrawer(Div body, Div aside, String label) {
        this.body = body;
        aside.setId(SIDEBAR_ID);
        toggle = new Button(VaadinIcon.MENU.create(), e -> setOpen(!open));
        toggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        toggle.addClassName("aster-drawer-toggle");
        toggle.setAriaLabel(label);
        toggle.setTooltipText(label);
        toggle.getElement().setAttribute("aria-controls", SIDEBAR_ID);
        Div backdrop = new Div();
        backdrop.addClassName("aster-drawer-backdrop");
        // 遮罩只是点击收起的区域，读屏跳过
        backdrop.getElement().setAttribute("aria-hidden", "true");
        backdrop.addClickListener(e -> close());
        body.add(backdrop);
        Shortcuts.addShortcutListener(aside, this::close, Key.ESCAPE).listenOn(aside);
        setOpen(false);
    }

    /** 顶栏里的开关按钮。 */
    Component toggle() {
        return toggle;
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        setOpen(false);
    }

    private void setOpen(boolean value) {
        open = value;
        body.setClassName(OPEN_CLASS, value);
        toggle.getElement().setAttribute("aria-expanded", Boolean.toString(value));
    }
}
