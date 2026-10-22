package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.KeyDownEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 窄屏抽屉的键盘与焦点（F31 / I5）：打开聚焦抽屉首个控件，Esc 在视图任意处收起，收起后焦点回到开关。 */
class ConversationDrawerTest {
    private final UI ui = new UI();
    private final Div root = new Div();
    private final Div body = new Div();
    private final Div aside = new Div();
    private final TextField hidden = new TextField();
    private final TextField search = new TextField();
    private final Button item = new Button("t1");
    private final ConversationDrawer drawer;
    private final Button toggle;

    ConversationDrawerTest() {
        // 只为让 UI 内部的待发 JS 调用可读：会话为 mock，锁检查放行
        com.vaadin.flow.server.VaadinSession session = org.mockito.Mockito.mock(com.vaadin.flow.server.VaadinSession.class);
        org.mockito.Mockito.when(session.hasLock()).thenReturn(true);
        ui.getInternals().setSession(session);
        hidden.setVisible(false);
        aside.add(new Div(hidden, search), item);
        body.add(aside);
        drawer = new ConversationDrawer(body, aside, "对话列表", root);
        toggle = (Button) drawer.toggle();
        root.add(toggle, body);
        ui.add(root);
        focused();
    }

    /** 本轮往返里被 focus() 的元素：Focusable.focus 以 JS 调用实现，可从待发调用读出。 */
    private List<Element> focused() {
        // executeJs 先挂到 beforeClientResponse，模拟一次响应收集后才进入待发队列
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(i -> i.getInvocation().getExpression().contains("focus"))
                .map(i -> Element.get(i.getOwner()))
                .toList();
    }

    private void key(String key) {
        ComponentUtil.fireEvent(root, new KeyDownEvent(root, key));
    }

    @Test
    void openingFocusesTheFirstVisibleControlInTheDrawer() {
        toggle.click();
        assertTrue(drawer.isOpen());
        assertEquals("true", toggle.getElement().getAttribute("aria-expanded"));
        assertEquals(List.of(search.getElement()), focused());
    }

    @Test
    void escapeFromTheToggleOrAnywhereInTheViewClosesAndRefocusesTheToggle() {
        toggle.click();
        focused();
        key("Enter");
        assertTrue(drawer.isOpen(), "其他按键不收起");
        key("Escape");
        assertFalse(drawer.isOpen());
        assertFalse(body.hasClassName(ConversationDrawer.OPEN_CLASS));
        assertEquals("false", toggle.getElement().getAttribute("aria-expanded"));
        assertEquals(List.of(toggle.getElement()), focused());
    }

    @Test
    void escapeWhileClosedDoesNothing() {
        key("Escape");
        assertFalse(drawer.isOpen());
        assertTrue(focused().isEmpty(), "已收起时 Esc 不抢焦点");
    }

    @Test
    void pickingAndBackdropAndSecondToggleCloseAndReturnFocus() {
        toggle.click();
        focused();
        drawer.close();
        assertEquals(List.of(toggle.getElement()), focused(), "选中 / 新建后焦点回到开关");

        toggle.click();
        focused();
        Div backdrop = (Div) body.getChildren().filter(c -> c.hasClassName("aster-drawer-backdrop")).findFirst().orElseThrow();
        ComponentUtil.fireEvent(backdrop, new com.vaadin.flow.component.ClickEvent<>(backdrop));
        assertFalse(drawer.isOpen());
        assertEquals(List.of(toggle.getElement()), focused());

        toggle.click();
        focused();
        toggle.click();
        assertFalse(drawer.isOpen());
        assertEquals(List.of(toggle.getElement()), focused());
    }
}
