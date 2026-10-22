package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyDownEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.util.Optional;

/**
 * 窄屏（≤ 48rem）下工作台左栏的抽屉开合：顶栏多出一颗「对话列表」开关，左栏收到屏幕外，
 * 打开时以浮层盖在聊天上方并带遮罩，聊天占满全宽。宽屏下开关与遮罩由 CSS 隐藏、左栏常驻，
 * 开合状态不影响版式。
 *
 * <p>状态只是工作台主体上的 {@code aster-drawer-open} 类与开关的 {@code aria-expanded}；
 * 选中 / 新建对话、点遮罩或按 Esc 都会收起。Esc 监听挂在整个视图上（焦点在开关、抽屉或别处都生效）。
 * 焦点：打开时移到抽屉里第一个可聚焦控件（搜索框或「新对话」），收起时回到开关，键盘与读屏用户不会丢失位置。
 */
final class ConversationDrawer {
    static final String OPEN_CLASS = "aster-drawer-open";
    static final String SIDEBAR_ID = "aster-conversations";

    private final Div body;
    private final Div aside;
    private final Button toggle;
    private boolean open;

    /**
     * @param body  工作台主体（含左栏与聊天列）；遮罩追加为它的子元素
     * @param aside 左栏；赋予 id 供开关的 aria-controls 引用
     * @param label 开关的本地化名称
     * @param keys  监听 Esc 的范围（工作台视图）
     */
    ConversationDrawer(Div body, Div aside, String label, Component keys) {
        this.body = body;
        this.aside = aside;
        aside.setId(SIDEBAR_ID);
        toggle = new Button(VaadinIcon.MENU.create(), e -> toggleOpen());
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
        // 只把 Esc 的 keydown 发到服务端；抽屉未打开时忽略，不抢焦点
        ComponentUtil.addListener(keys, KeyDownEvent.class, e -> {
            if (e.getKey().matches(Key.ESCAPE.getKeys().get(0))) {
                close();
            }
        }, reg -> reg.setFilter("event.key === 'Escape'"));
        setOpen(false);
    }

    /** 顶栏里的开关按钮。 */
    Component toggle() {
        return toggle;
    }

    boolean isOpen() {
        return open;
    }

    /** 收起；仅在原本打开时把焦点还给开关。 */
    void close() {
        if (!open) {
            return;
        }
        setOpen(false);
        toggle.focus();
    }

    private void toggleOpen() {
        if (open) {
            close();
            return;
        }
        setOpen(true);
        firstFocusable(aside).ifPresent(Focusable::focus);
    }

    /** 抽屉里按文档顺序的第一个可聚焦控件。 */
    private static Optional<Focusable<?>> firstFocusable(Component root) {
        if (root instanceof Focusable<?> f && root.isVisible()) {
            return Optional.of(f);
        }
        return root.getChildren().map(ConversationDrawer::firstFocusable)
                .flatMap(Optional::stream).findFirst();
    }

    private void setOpen(boolean value) {
        open = value;
        body.setClassName(OPEN_CLASS, value);
        toggle.getElement().setAttribute("aria-expanded", Boolean.toString(value));
    }
}
