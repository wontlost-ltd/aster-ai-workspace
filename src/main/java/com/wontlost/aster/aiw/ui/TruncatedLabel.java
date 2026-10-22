package com.wontlost.aster.aiw.ui;

import com.wontlost.agui.AgUiChat;
import tools.jackson.databind.JsonNode;

/**
 * 琥珀色徽标「回答已被截断」（F5）：cloud 的 final 帧带 {@code truncated: true}（上游 stop reason 为 max_tokens）时，
 * agent 发状态增量 {@code /truncated}，这里经 state-changed 读取。每次 run 开始时隐藏。
 * 以 {@code role=status} 让读屏在出现时播报。
 */
final class TruncatedLabel extends Badge {
    static final String KEY = "ui.truncated";

    TruncatedLabel(AgUiChat chat, String text) {
        setText(text);
        setTone(Tone.WARNING);
        getElement().setAttribute("role", "status");
        setVisible(false);
        chat.addRunStartedListener(e -> setVisible(false));
        chat.addStateChangedListener(e -> setVisible(truncated(e.getState())));
    }

    static boolean truncated(JsonNode state) {
        return state != null && state.path("truncated").asBoolean(false);
    }
}
