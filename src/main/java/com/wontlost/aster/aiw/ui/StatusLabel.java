package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.html.Span;
import com.wontlost.agui.AgUiChat;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * 小字显示本次 run 的校验结论与修复进度（ADR 0049 §2.3「以提示显示」）。
 *
 * <p>agent 以状态增量发出 {@code /validated}、{@code /validationError}、{@code /repair}，并在每次 run 开始时
 * 把三者置 null；这里经 state-changed 读取。优先级：已有校验结论 &gt; 修复中 &gt; 不显示。
 * run 开始时立即隐藏，避免上一次的结论挂在新回答旁边。
 */
final class StatusLabel extends Span {
    static final String VALIDATED = "ui.status.validated";
    static final String INVALID = "ui.status.invalid";
    static final String REPAIRING = "ui.status.repairing";
    /** 错误详情最多显示的字符数，完整信息仍在对话里。 */
    static final int MAX_ERROR = 80;

    private final Function<String, String> texts;

    StatusLabel(AgUiChat chat, Function<String, String> texts) {
        this.texts = texts;
        setVisible(false);
        getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        chat.addRunStartedListener(e -> setVisible(false));
        chat.addStateChangedListener(e -> show(e.getState()));
    }

    private void show(JsonNode state) {
        String text = state == null ? null : describe(state);
        setVisible(text != null);
        if (text != null) {
            setText(text);
        }
    }

    private String describe(JsonNode state) {
        JsonNode validated = state.path("validated");
        if (validated.isBoolean()) {
            return validated.asBoolean() ? texts.apply(VALIDATED) : invalid(state.path("validationError"));
        }
        JsonNode repair = state.path("repair");
        if (repair.isMissingNode() || repair.isNull()) {
            return null;
        }
        return texts.apply(REPAIRING).replace("{0}", repair.asString(""));
    }

    private String invalid(JsonNode error) {
        String detail = error.isObject() ? error.path("message").asString(error.toString())
                : error.isValueNode() && !error.isNull() ? error.asString("") : "";
        String base = texts.apply(INVALID);
        if (detail.isBlank()) {
            return base;
        }
        String shown = detail.length() > MAX_ERROR ? detail.substring(0, MAX_ERROR) + "…" : detail;
        return base + texts.apply("ui.status.separator") + shown;
    }
}
