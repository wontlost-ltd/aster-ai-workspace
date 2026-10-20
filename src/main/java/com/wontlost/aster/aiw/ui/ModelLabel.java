package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.html.Span;
import com.wontlost.agui.AgUiChat;
import tools.jackson.databind.JsonNode;

/**
 * 小字显示本次 run 的实际模型（ADR 0049 §2.3）：agent 在 {@code meta} 帧时发状态增量 {@code /model}，
 * 这里经聊天的 state-changed 事件读取。状态里没有模型（如清空对话）时保留上次显示，未收到过则隐藏。
 */
final class ModelLabel extends Span {
    static final String KEY = "ui.model";

    /** @param template 本地化模板，{@code {0}} 处填入模型名 */
    ModelLabel(AgUiChat chat, String template) {
        setVisible(false);
        getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        chat.addStateChangedListener(e -> show(e.getState(), template));
    }

    private void show(JsonNode state, String template) {
        String model = state == null ? "" : state.path("model").asString("");
        if (model.isBlank()) {
            return;
        }
        setText(template.replace("{0}", model));
        setVisible(true);
    }
}
