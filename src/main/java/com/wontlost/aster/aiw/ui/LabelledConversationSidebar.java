package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasAriaLabel;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextField;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationSidebar;
import com.wontlost.agui.pro.conversations.ConversationSidebarI18n;
import com.wontlost.aster.aiw.i18n.I18nBeans;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pro 会话侧栏补齐无障碍名称：库里的重命名 / 删除只是图标按钮（文字只在 tooltip 描述里），
 * 「新对话」的名称会带上图标前的空白，搜索框只有 placeholder。库没有开放这些钩子，
 * 这里在每次重绘（列表、选中、文案变化）之后按 DOM 结构补 aria-label，不改动库的行为。
 *
 * <p>名称含对话标题（「重命名对话 X」），读屏用户在列表里能分清每颗按钮作用于哪条对话。
 */
final class LabelledConversationSidebar extends ConversationSidebar {
    static final String RENAME_KEY = "sidebar.renameConversation";
    static final String DELETE_KEY = "sidebar.deleteConversation";
    private static final String THREAD_ATTRIBUTE = "data-thread-id";

    private final Function<String, String> texts;

    LabelledConversationSidebar(Function<String, String> texts) {
        this.texts = texts;
        setI18n(I18nBeans.fill(new ConversationSidebarI18n(), "sidebar.", texts));
    }

    @Override
    public void setConversations(List<Conversation> items) {
        super.setConversations(items);
        label();
    }

    @Override
    public void setSelected(String threadId) {
        super.setSelected(threadId);
        label();
    }

    @Override
    public void setI18n(ConversationSidebarI18n i18n) {
        super.setI18n(i18n);
        label();
    }

    /** 侧栏结构（库 0.1.x）：搜索框、新对话、列表；列表项为带 data-thread-id 的 Div，末尾是动作按钮组。 */
    private void label() {
        getContent().getChildren().forEach(child -> {
            if (child instanceof TextField search) {
                search.setAriaLabel(texts.apply("sidebar.searchPlaceholder"));
            } else if (child instanceof Button newChat) {
                newChat.setAriaLabel(texts.apply("sidebar.newChat"));
            } else {
                labelItems(child);
            }
        });
    }

    private void labelItems(Component list) {
        Map<String, String> titles = getConversations().stream()
                .collect(Collectors.toMap(Conversation::threadId, Conversation::title, (a, b) -> a));
        list.getChildren().forEach(item -> {
            String title = titles.get(item.getElement().getAttribute(THREAD_ATTRIBUTE));
            if (title == null) {
                return;
            }
            List<Button> actions = item.getChildren().flatMap(Component::getChildren)
                    .filter(Button.class::isInstance).map(Button.class::cast).toList();
            if (actions.size() == 2) {
                label(actions.get(0), RENAME_KEY, title);
                label(actions.get(1), DELETE_KEY, title);
            }
        });
    }

    private void label(HasAriaLabel button, String key, String title) {
        button.setAriaLabel(texts.apply(key).replace("{0}", title));
    }
}
