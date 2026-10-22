package com.wontlost.aster.aiw.ui;

import com.wontlost.agui.AgUiChat;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * 徽标显示本次 run 的校验结论与修复进度（ADR 0049 §2.3「以提示显示」）：已校验为绿、未通过为红、修复中为紫。
 *
 * <p>agent 以状态增量发出 {@code /validated}、{@code /validationError}、{@code /repair}，并在每次 run 开始时
 * 把三者置 null；这里经 state-changed 读取。优先级：已有校验结论 &gt; 修复中 &gt; 不显示。
 * run 开始时立即隐藏，避免上一次的结论挂在新回答旁边。
 */
final class StatusLabel extends Badge {
    static final String VALIDATED = "ui.status.validated";
    static final String INVALID = "ui.status.invalid";
    static final String REPAIRING = "ui.status.repairing";
    /** 错误详情最多显示的字符数，完整信息仍在对话里。 */
    static final int MAX_ERROR = 80;

    /** 一次状态对应的徽标：色调 + 文案。 */
    record Status(Tone tone, String text) {}

    private final Function<String, String> texts;

    StatusLabel(AgUiChat chat, Function<String, String> texts) {
        this.texts = texts;
        setVisible(false);
        chat.addRunStartedListener(e -> setVisible(false));
        chat.addStateChangedListener(e -> show(e.getState()));
    }

    private void show(JsonNode state) {
        Status status = state == null ? null : describe(state);
        setVisible(status != null);
        if (status != null) {
            setTone(status.tone());
            setText(status.text());
            // 截断的错误详情仍可悬停查看完整文字
            setTitle(status.text());
        }
    }

    /** 状态 → 徽标；无可显示内容时为 null。 */
    Status describe(JsonNode state) {
        JsonNode validated = state.path("validated");
        if (validated.isBoolean()) {
            return validated.asBoolean() ? new Status(Tone.SUCCESS, texts.apply(VALIDATED))
                    : new Status(Tone.DANGER, invalid(state.path("validationError")));
        }
        JsonNode repair = state.path("repair");
        if (repair.isMissingNode() || repair.isNull()) {
            return null;
        }
        return new Status(Tone.PRIMARY, texts.apply(REPAIRING).replace("{0}", repair.asString("")));
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
