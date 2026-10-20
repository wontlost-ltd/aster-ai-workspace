package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.shared.Registration;
import com.wontlost.aster.aiw.agent.RunRequestBuilder.Mode;
import java.util.function.Consumer;
import java.util.function.Function;

/** 生成 / 建议 / 问答 三选一；侧栏默认建议，工作台默认问答。 */
public class ModeSelector extends Composite<RadioButtonGroup<Mode>> {

    public ModeSelector(Mode initial, Function<String, String> texts) {
        RadioButtonGroup<Mode> group = getContent();
        group.setItems(Mode.values());
        group.setItemLabelGenerator(mode -> texts.apply("mode." + mode.wire()));
        group.getElement().setAttribute("aria-label", texts.apply("mode.label"));
        group.setValue(initial);
    }

    public Mode getMode() {
        return getContent().getValue();
    }

    /** 只在选中新值时通知（清空不会发生：组件不提供取消选择）。 */
    public Registration addModeChangeListener(Consumer<Mode> listener) {
        return getContent().addValueChangeListener(e -> {
            if (e.getValue() != null) {
                listener.accept(e.getValue());
            }
        });
    }
}
