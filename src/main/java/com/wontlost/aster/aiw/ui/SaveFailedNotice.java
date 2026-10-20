package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;

/** 「本次对话未能保存」提示（ADR 0049 §2.7）：对话照常进行，只在界面上告知。 */
final class SaveFailedNotice {
    static final String KEY = "ui.saveFailed";
    private static final int DURATION_MS = 6000;

    private SaveFailedNotice() {}

    /** 绑定到当前 UI；不在 UI 线程内（例如单测）时为空操作。 */
    static Runnable forCurrentUi() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return () -> {};
        }
        return () -> ui.access(() -> show(ui.getTranslation(KEY)));
    }

    private static void show(String text) {
        Notification notice = Notification.show(text, DURATION_MS, Notification.Position.TOP_CENTER);
        notice.addThemeVariants(NotificationVariant.LUMO_WARNING);
    }
}
