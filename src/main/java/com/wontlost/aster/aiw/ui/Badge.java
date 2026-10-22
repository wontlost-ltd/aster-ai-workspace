package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.html.Span;
import java.util.Locale;

/** 小徽标：外观由 {@code aster-badge} 与 {@code data-tone} 决定，取值见 {@link Tone}。 */
class Badge extends Span {
    /** 徽标色调；{@link #attribute()} 即 CSS 中 {@code data-tone} 的取值。 */
    enum Tone {
        NEUTRAL, SUCCESS, DANGER, PRIMARY;

        String attribute() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    Badge() {
        addClassName("aster-badge");
        setTone(Tone.NEUTRAL);
    }

    final void setTone(Tone tone) {
        getElement().setAttribute("data-tone", tone.attribute());
    }

    Tone getTone() {
        return Tone.valueOf(getElement().getAttribute("data-tone").toUpperCase(Locale.ROOT));
    }
}
