package com.wontlost.aster.aiw.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wontlost.agui.AgUiChatI18n;
import com.wontlost.agui.pro.conversations.ConversationSidebarI18n;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiwI18nProviderTest {
    private final AiwI18nProvider i18n = new AiwI18nProvider();
    private static final List<String> LOCALES = List.of("en", "zh", "de", "hi");

    @Test
    void providesTheFourUiLocales() {
        assertEquals(Set.of("en", "zh", "de", "hi"),
                Set.copyOf(i18n.getProvidedLocales().stream().map(Locale::getLanguage).toList()));
    }

    @Test
    void everyBundleHasTheSameKeysAsEnglish() {
        Set<String> en = i18n.keys("en");
        for (String locale : LOCALES) {
            assertEquals(en, i18n.keys(locale), locale);
        }
    }

    @Test
    void everyChatAndSidebarFieldIsTranslated() {
        for (String locale : LOCALES) {
            for (String key : I18nBeans.keys(AgUiChatI18n.class, "chat.")) {
                assertTrue(i18n.keys(locale).contains(key), locale + " " + key);
            }
            for (String key : I18nBeans.keys(ConversationSidebarI18n.class, "sidebar.")) {
                assertTrue(i18n.keys(locale).contains(key), locale + " " + key);
            }
        }
    }

    @Test
    void saveFailedTextsAreVerbatim() {
        assertEquals("This conversation could not be saved", i18n.text("en", "ui.saveFailed"));
        assertEquals("本次对话未能保存", i18n.text("zh", "ui.saveFailed"));
        assertEquals("Diese Unterhaltung konnte nicht gespeichert werden", i18n.text("de", "ui.saveFailed"));
        assertEquals("यह बातचीत सहेजी नहीं जा सकी", i18n.text("hi", "ui.saveFailed"));
    }

    @Test
    void hindiIsInDevanagari() {
        String back = i18n.text("hi", "workspace.back");
        assertTrue(back.codePoints().anyMatch(c -> c >= 0x0900 && c <= 0x097F), back);
    }

    @Test
    void unsupportedLocaleFallsBackToEnglishAndUnknownKeyToItself() {
        assertEquals(i18n.text("en", "mode.ask"), i18n.getTranslation("mode.ask", Locale.FRENCH));
        assertEquals("no.such.key", i18n.getTranslation("no.such.key", Locale.GERMAN));
        assertNotEquals(i18n.text("en", "mode.ask"), i18n.text("zh", "mode.ask"));
    }

    @Test
    void fillsBeansThroughTheirSetters() {
        AgUiChatI18n chat = I18nBeans.fill(new AgUiChatI18n(), "chat.", key -> i18n.text("zh", key));
        assertEquals(i18n.text("zh", "chat.insertCode"), chat.getInsertCode());
        assertEquals(i18n.text("zh", "chat.send"), chat.getSend());
    }
}
