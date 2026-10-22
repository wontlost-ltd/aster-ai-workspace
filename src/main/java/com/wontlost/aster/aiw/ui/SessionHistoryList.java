package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.wontlost.agui.AgUiChat;
import java.util.function.Function;

/**
 * 无 Pro 会话授权时工作台左栏：本会话内的对话列表 + 新对话。每次运行结束、切换或新建后重绘，
 * 当前线程以浅紫底色与 {@code aria-current} 标出。
 */
final class SessionHistoryList extends Composite<VerticalLayout> {
    private final SessionHistory history;
    private final HistoryBinding binding;
    private final AgUiChat chat;
    private final Function<String, String> texts;
    private final Div items = new Div();

    SessionHistoryList(SessionHistory history, HistoryBinding binding, AgUiChat chat, Function<String, String> texts) {
        this.history = history;
        this.binding = binding;
        this.chat = chat;
        this.texts = texts;
        Button newChat = new Button(texts.apply("history.newChat"), VaadinIcon.PLUS.create(), e -> {
            binding.newConversation();
            refresh();
        });
        newChat.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        newChat.setWidthFull();
        items.setWidthFull();
        items.addClassName("aster-history-items");
        Span title = new Span(texts.apply("history.title"));
        title.addClassName("aster-history-title");
        getContent().setPadding(false);
        getContent().setSpacing(false);
        getContent().addClassName("aster-history");
        getContent().add(newChat, title, items);
        chat.addRunFinishedListener(e -> refresh());
        refresh();
    }

    void refresh() {
        items.removeAll();
        if (history.threadIds().isEmpty()) {
            Span empty = new Span(texts.apply("history.empty"));
            empty.addClassName("aster-history-empty");
            items.add(empty);
            return;
        }
        for (String threadId : history.threadIds()) {
            items.add(item(threadId, threadId.equals(chat.getThreadId())));
        }
    }

    private Button item(String threadId, boolean current) {
        Button button = new Button(history.title(threadId), e -> {
            binding.restore(threadId);
            refresh();
        });
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        button.setWidthFull();
        if (current) {
            button.addClassName("aster-current");
            button.getElement().setAttribute("aria-current", "true");
        }
        return button;
    }
}
