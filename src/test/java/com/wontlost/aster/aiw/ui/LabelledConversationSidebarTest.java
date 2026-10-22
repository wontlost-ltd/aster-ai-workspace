package com.wontlost.aster.aiw.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextField;
import com.wontlost.agui.model.Message;
import com.wontlost.agui.pro.conversations.Conversation;
import com.wontlost.agui.pro.conversations.ConversationScope;
import com.wontlost.aster.aiw.i18n.AiwI18nProvider;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Pro 会话侧栏的无障碍名称（F14）：图标按钮带对话标题、新对话无前导空白、搜索框有名称。 */
class LabelledConversationSidebarTest {
    private static final ConversationScope SCOPE = new ConversationScope("u1", "u1");
    private final AiwI18nProvider i18n = new AiwI18nProvider();

    private LabelledConversationSidebar sidebar(String locale) {
        return new LabelledConversationSidebar(key -> i18n.text(locale, key));
    }

    private static Conversation conversation(String threadId, String title) {
        return Conversation.start(threadId, SCOPE, "aster", List.of(Message.user("hi")))
                .withTitle(title);
    }

    private static <T extends Component> Stream<T> find(Component root, Class<T> type) {
        Stream<T> self = type.isInstance(root) ? Stream.of(type.cast(root)) : Stream.empty();
        return Stream.concat(self, root.getChildren().flatMap(c -> find(c, type)));
    }

    private static List<String> labels(Component root) {
        return find(root, Button.class).map(b -> b.getAriaLabel().orElse("")).toList();
    }

    @Test
    void iconButtonsNameTheConversationTheyActOn() {
        LabelledConversationSidebar sidebar = sidebar("zh");
        sidebar.setConversations(List.of(conversation("t1", "贷款规则"), conversation("t2", "年龄检查")));

        assertEquals(List.of("新对话", "重命名对话 贷款规则", "删除对话 贷款规则", "重命名对话 年龄检查", "删除对话 年龄检查"),
                labels(sidebar));
    }

    @Test
    void namesSurviveReRendersAndFollowTheLocale() {
        LabelledConversationSidebar sidebar = sidebar("de");
        sidebar.setConversations(List.of(conversation("t1", "Kredit")));
        sidebar.setSelected("t1");

        assertEquals(List.of("Neue Unterhaltung", "Unterhaltung Kredit umbenennen", "Unterhaltung Kredit löschen"),
                labels(sidebar));
    }

    @Test
    void searchFieldHasAnAccessibleName() {
        TextField search = find(sidebar("en"), TextField.class).findFirst().orElseThrow();
        assertEquals("Search conversations", search.getAriaLabel().orElseThrow());
    }

    @Test
    void everyLocaleHasBothTemplates() {
        for (String locale : List.of("en", "zh", "de", "hi")) {
            for (String key : List.of(LabelledConversationSidebar.RENAME_KEY, LabelledConversationSidebar.DELETE_KEY)) {
                assertEquals(true, i18n.text(locale, key).contains("{0}"), locale + " " + key);
            }
        }
    }
}
