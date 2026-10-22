package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.html.Span;
import com.wontlost.agui.AgUiChat;
import tools.jackson.databind.JsonNode;

/**
 * 琥珀色徽标「回答已被截断」（F5）：cloud 的帧带 {@code truncated: true}（上游 stop reason 为 max_tokens），
 * 或 generate 流缺少 final 帧时，agent 发状态增量 {@code /truncated}，这里经 state-changed 读取。每次 run 开始时撤下。
 *
 * <p>读屏播报：本组件是常驻的 {@code role=status} 容器（空时 CSS 将其移出排版但仍在无障碍树里），
 * 截断时才把徽标作为子元素放进去。读屏只可靠地播报「已存在的 live region 内容变化」，
 * 若像其它徽标那样切换 hidden，出现时往往不会被读出。
 */
final class TruncatedLabel extends Span {
    static final String KEY = "ui.truncated";

    private final Badge badge = new Badge();

    TruncatedLabel(AgUiChat chat, String text) {
        addClassName("aster-live");
        getElement().setAttribute("role", "status");
        badge.setText(text);
        badge.setTone(Badge.Tone.WARNING);
        chat.addRunStartedListener(e -> show(false));
        chat.addStateChangedListener(e -> show(truncated(e.getState())));
    }

    static boolean truncated(JsonNode state) {
        return state != null && state.path("truncated").asBoolean(false);
    }

    /** 当前是否显示截断徽标。 */
    boolean showing() {
        return badge.getParent().isPresent();
    }

    Badge badge() {
        return badge;
    }

    private void show(boolean on) {
        if (on && !showing()) {
            add(badge);
        } else if (!on && showing()) {
            remove(badge);
        }
    }
}
